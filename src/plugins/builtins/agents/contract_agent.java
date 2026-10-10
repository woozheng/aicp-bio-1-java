import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * builtins/agents/contract_agent — 契约智能体
 *
 * 读插件源码，用 LLM 提取契约（action / params / returns）。
 *
 * 缓存：.contract.json 存在且 source_hash 一致 → 直接返回。
 * 失败：LLM 提取失败 → fallback（正则粗扫 action 名）。
 *
 * receiver 从"文件路径"推：builtins/agents/contract_agent
 */
public class contract_agent implements Plugin {

    private static final String PLUGINS_ROOT = "src/plugins";

    private static final String EXTRACTOR_PROMPT = """
        你是一个契约提取专家。你的唯一任务：读插件源码，提取标准契约。

        ## 你收到的源码是完整的插件文件

        ## 你的工作方式

        ### 第一步：定位 execute 函数
        找到 execute(Envelop envelop, Agent agent) 方法。

        ### 第二步：提取所有 action 名称
        扫描 switch / if-else 里的 action 分发逻辑：
        - switch (action) { case "xxx": }
        - if ("xxx".equals(action))
        去重，列出所有 action。

        ### 第三步：逐个 action 提取参数
        对每个 action 的代码块，扫描 envelop.payload 的引用：
        - envelop.payload.get("xxx") 是非必填
        - 没有默认值、直接 .get() 后必用 → 必填

        参数类型推断：
        - 数字 → number
        - 字符串 → string
        - true/false → boolean
        - List → array
        - Map → object

        ### 第四步：提取返回字段
        找到 envelop.payload = Map.of(...) 里的顶层 key。

        ### 第五步：生成标准契约

        {
          "plugin": "插件路由名",
          "description": "一句话说明这个插件做什么",
          "actions": {
            "action名": {
              "description": "功能描述",
              "params": [
                {"name": "参数名", "required": true/false, "type": "类型", "desc": "说明"}
              ],
              "returns": [
                {"name": "返回字段", "type": "类型", "desc": "说明"}
              ]
            }
          }
        }

        ## 核心原则
        1. 源码是唯一真相，不要猜
        2. action 名和参数名必须 100% 准确
        3. 完整覆盖，不要遗漏
        4. 输出纯 JSON，不要包含其他文字
        """;

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "get";

        String pluginName = str(envelop.payload.get("plugin")).trim();
        if (pluginName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 plugin 参数");
            return envelop;
        }

        // 统一去后缀
        if (pluginName.endsWith(".java")) {
            pluginName = pluginName.substring(0, pluginName.length() - 5);
        } else if (pluginName.endsWith(".ts")) {
            pluginName = pluginName.substring(0, pluginName.length() - 3);
        }

        Path pluginFile = Path.of(PLUGINS_ROOT, pluginName + ".java");
        Path contractFile = Path.of(PLUGINS_ROOT, pluginName + ".contract.json");

        switch (action) {
            case "get":
                return handleGet(envelop, agent, pluginName, pluginFile, contractFile);
            case "refresh":
                return handleRefresh(envelop, agent, pluginName, pluginFile, contractFile);
            case "get_execute_only":
                return handleGetExecuteOnly(envelop, pluginName, pluginFile);
            default:
                envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // get：有契约且 hash 一致 → 直接返回；否则 LLM 提取
    // ============================================================

    private Envelop handleGet(Envelop envelop, Agent agent,
                              String pluginName, Path pluginFile, Path contractFile) {
        if (!Files.exists(pluginFile)) {
            envelop.payload = Map.of("ok", false, "error",
                "插件源码不存在: " + pluginFile.toString().replace("\\", "/"));
            return envelop;
        }

        String sourceCode;
        try {
            sourceCode = Files.readString(pluginFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读取源码失败: " + e.getMessage());
            return envelop;
        }

        String currentHash = md5(sourceCode);

        // 1. 优先读 .contract.json，且 hash 一致
        if (Files.exists(contractFile)) {
            try {
                String json = Files.readString(contractFile, StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> contract = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);

                if (currentHash.equals(contract.get("source_hash"))) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("ok", true);
                    result.put("plugin", pluginName);
                    result.put("contract", contract);
                    result.put("source", "existing_contract");
                    result.put("contract_file", contractFile.toString().replace("\\", "/"));
                    envelop.payload = result;
                    return envelop;
                }
            } catch (Exception ignored) {}
        }

        // 2. LLM 提取
        Map<String, Object> llmResult = callLlmExtract(agent, pluginName, sourceCode);

        Map<String, Object> result = new HashMap<>();
        result.put("plugin", pluginName);

        if (Boolean.TRUE.equals(llmResult.get("ok"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) llmResult.get("contract");
            contract.put("source_hash", currentHash);

            // 保存
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(contract);
                Files.writeString(contractFile, json, StandardCharsets.UTF_8);
                result.put("contract_saved", contractFile.toString().replace("\\", "/"));
            } catch (Exception e) {
                result.put("contract_saved", "保存失败: " + e.getMessage());
            }

            result.put("ok", true);
            result.put("contract", contract);
            result.put("source", "llm_extraction");
        } else {
            // fallback
            result.put("ok", false);
            result.put("error", llmResult.get("error"));
            result.put("source", "extraction_failed");
            result.put("fallback", buildFallbackContract(pluginName, sourceCode));
        }

        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // refresh：强制重新提取
    // ============================================================

    private Envelop handleRefresh(Envelop envelop, Agent agent,
                                  String pluginName, Path pluginFile, Path contractFile) {
        if (!Files.exists(pluginFile)) {
            envelop.payload = Map.of("ok", false, "error",
                "插件源码不存在: " + pluginFile.toString().replace("\\", "/"));
            return envelop;
        }

        String sourceCode;
        try {
            sourceCode = Files.readString(pluginFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读取源码失败: " + e.getMessage());
            return envelop;
        }

        String currentHash = md5(sourceCode);
        Map<String, Object> llmResult = callLlmExtract(agent, pluginName, sourceCode);

        Map<String, Object> result = new HashMap<>();
        result.put("plugin", pluginName);

        if (Boolean.TRUE.equals(llmResult.get("ok"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> contract = (Map<String, Object>) llmResult.get("contract");
            contract.put("source_hash", currentHash);

            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(contract);
                Files.writeString(contractFile, json, StandardCharsets.UTF_8);
                result.put("contract_saved", contractFile.toString().replace("\\", "/"));
            } catch (Exception e) {
                result.put("contract_saved", "保存失败: " + e.getMessage());
            }

            result.put("ok", true);
            result.put("contract", contract);
            result.put("source", "refreshed");
        } else {
            result.put("ok", false);
            result.put("error", llmResult.get("error"));
            result.put("source", "extraction_failed");
            result.put("fallback", buildFallbackContract(pluginName, sourceCode));
        }

        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // get_execute_only：返回 execute 方法源码
    // ============================================================

    private Envelop handleGetExecuteOnly(Envelop envelop, String pluginName, Path pluginFile) {
        if (!Files.exists(pluginFile)) {
            envelop.payload = Map.of("ok", false, "error",
                "插件源码不存在: " + pluginFile.toString().replace("\\", "/"));
            return envelop;
        }

        String sourceCode;
        try {
            sourceCode = Files.readString(pluginFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读取源码失败: " + e.getMessage());
            return envelop;
        }

        String[] lines = sourceCode.split("\n");
        int executeStart = -1;
        int executeEnd = -1;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].trim();
            if (executeStart == -1 && stripped.contains("Envelop execute(")) {
                executeStart = i;
                continue;
            }
            if (executeStart != -1) {
                // 下一个"顶层方法 / 类"作为结束
                if ((stripped.startsWith("public ") || stripped.startsWith("private ")
                    || stripped.startsWith("protected ") || stripped.startsWith("static "))
                    && stripped.contains("(") && !stripped.contains("execute(")) {
                    executeEnd = i;
                    break;
                }
            }
        }

        String snippet;
        if (executeStart == -1) {
            snippet = sourceCode;
        } else if (executeEnd == -1) {
            snippet = String.join("\n", Arrays.copyOfRange(lines, executeStart, lines.length));
        } else {
            snippet = String.join("\n", Arrays.copyOfRange(lines, executeStart, executeEnd));
        }

        envelop.payload = Map.of("ok", true, "plugin", pluginName, "source_snippet", snippet);
        return envelop;
    }

    // ============================================================
    // LLM 提取
    // ============================================================

    private Map<String, Object> callLlmExtract(Agent agent, String pluginName, String sourceCode) {
        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            return Map.of("ok", false, "error", "LLM 未配置");
        }

        // 截断源码（防 token 爆）
        String truncated = sourceCode.length() > 30000
            ? sourceCode.substring(0, 30000) + "\n\n... (源码过长，已截断)"
            : sourceCode;

        String prompt = EXTRACTOR_PROMPT
            + "\n\n## 插件源码\n\n插件路径：" + pluginName + "\n\n" + truncated
            + "\n\n请提取契约，只输出 JSON。";

        try {
            Map<String, Object> result = llm.chatJson(List.of(
                Map.of("role", "user", "content", prompt)
            ));

            if (result == null) {
                return Map.of("ok", false, "error", "LLM 返回为空");
            }

            if (result.get("error") != null) {
                return Map.of("ok", false, "error", result.get("error").toString());
            }

            Object actionsObj = result.get("actions");
            if (!(actionsObj instanceof Map<?, ?> actions) || actions.isEmpty()) {
                return Map.of("ok", false, "error", "LLM 返回缺少 actions 字段或为空");
            }

            result.putIfAbsent("plugin", pluginName);
            return Map.of("ok", true, "contract", result);

        } catch (Exception e) {
            return Map.of("ok", false, "error", "LLM 调用失败: " + e.getMessage());
        }
    }

    // ============================================================
    // fallback：正则粗扫 action 名
    // ============================================================

    private Map<String, Object> buildFallbackContract(String pluginName, String sourceCode) {
        Map<String, Object> actions = new LinkedHashMap<>();

        // case "xxx":
        Matcher m1 = Pattern.compile("case\\s+[\"']([^\"']+)[\"']\\s*:").matcher(sourceCode);
        while (m1.find()) {
            String name = m1.group(1);
            if (!actions.containsKey(name)) {
                actions.put(name, Map.of(
                    "description", "(fallback: LLM extraction failed)",
                    "params", List.of(),
                    "returns", List.of(),
                    "notes", "此契约由正则粗扫生成，可能不完整"
                ));
            }
        }

        // if ("xxx".equals(action))
        Matcher m2 = Pattern.compile("[\"']([^\"']+)[\"']\\s*\\.\\s*equals\\s*\\(\\s*action\\s*\\)")
            .matcher(sourceCode);
        while (m2.find()) {
            String name = m2.group(1);
            if (!actions.containsKey(name)) {
                actions.put(name, Map.of(
                    "description", "(fallback: LLM extraction failed)",
                    "params", List.of(),
                    "returns", List.of()
                ));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plugin", pluginName);
        result.put("actions", actions);
        result.put("_fallback", true);
        result.put("_fallback_note", "LLM 提取失败，此契约由正则粗扫生成");
        return result;
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
