import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;
import com.aicp.runtime.LLM;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * builtins/tools/fix_tool — 修复已有插件（Java 版）
 *
 * 流程：
 *   1. 解析 target → 项目名
 *   2. ★ 让 LLM 判断是前端修复还是后端修复
 *   3a. 前端 → 调 frontend_agent.fix
 *   3b. 后端 → 现有逻辑（fix_design → local/regenerate）
 *   4. 等 2 秒，验证
 */
public class fix_tool implements Plugin {

    private static final String PROJECTS_DIR = "src/plugins/applications";
    private static final int MAX_BACKEND_SOURCE_CHARS = 30000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============================================================
    // scope 判断 prompt
    // ============================================================

    private static final String SCOPE_JUDGE_SYSTEM = """
        你是 AICP 项目的修复需求分类器。你的唯一任务：读修复需求，判断它应该走「前端修复」还是「后端修复」。

        ============================================================
        判断规则
        ============================================================

        【后端修复】：
        - 改 API / action / 参数 / 返回值 / 字段
        - 改数据源 / 数据库 / 缓存
        - 改业务逻辑 / 算法
        - 加校验 / 超时 / 错误处理
        - 加 @DEPS 依赖
        - 改 receiver / 路由
        - 接口协议变化

        【前端修复】：
        - 改页面布局 / 样式 / 颜色
        - 改按钮 / 表单 / 输入框
        - 改交互 / 点击 / 显示
        - 改 HTML / CSS / JS
        - 加页面 / 加组件

        【两者都有】：
        - 需求同时涉及前端和后端
        - 例：「加搜索功能，后端加搜索接口，前端加搜索框」
        - 这种优先走后端（后端先改，前端再改）

        ============================================================
        输出格式（只输出 JSON，不要任何其他文字）
        ============================================================

        {
          "scope": "backend",
          "reason": "为什么这么判断（一句话）"
        }

        规则：
        1. scope 只能是 backend / frontend
        2. 两者都有时，scope=backend
        3. 不确定时，scope=backend（保守）
        4. 只输出 JSON，不要代码块标记，不要解释
        """;

    // ============================================================
    // fix_design prompt
    // ============================================================

    private static final String FIX_DESIGN_SYSTEM = """
        你是 AICP 修复方案设计师。你的唯一任务：读项目现状 + 修复需求，输出修复方案 JSON。

        ============================================================
        AICP 项目结构（Java 版）
        ============================================================

        一个项目 = 后端插件（src/plugins/applications/{项目名}/xxx.java）

        后端插件：
        - receiver: applications/{项目名}/xxx
        - 类名 = 文件名（不含 .java）
        - 实现 com.aicp.core.Plugin 接口
        - 有 execute(Envelop envelop, Agent agent) 方法
        - action 从 envelop.payload.get("action") 取
        - 返回 envelop.payload = Map.of("ok", true/false, ...)

        ============================================================
        核心判断：mode（local / regenerate）
        ============================================================

        mode=local（局部改）：
        - 改字段、改参数、改返回值、改逻辑
        - 不涉及新 import、新方法、新协议
        - 改动局限在现有方法内
        - 例：「加个 priority 字段」「改错误信息」「列表加筛选」

        mode=regenerate（重生成）：
        - 涉及新依赖（@DEPS）
        - 涉及新结构（多 action 协作、状态机、队列）
        - 涉及多个文件
        - 现有代码要"重构"才能实现
        - 例：「加流式输出」「加数据库支持」「改成多步骤工作流」
        - ★ 改数据源（如从本地 JSON 改成 HTTP API）→ regenerate

        判断规则：
        - 关键词 local：改 / 调 / 加字段 / 加显示 / 加筛选 / 改文案 / 改顺序 / 改参数
        - 关键词 regenerate：加流式 / 加推送 / 加认证 / 加数据库 / 加依赖 / 重构 / 重写 / 改数据源

        不确定时：默认 regenerate（更稳）。

        ============================================================
        输出格式（只输出 JSON，不要任何其他文字）
        ============================================================

        {
          "mode": "local",
          "scope": "backend",
          "reason": "为什么这么判断（一句话）",
          "backend_changes": [
            {
              "file": "task_api.java",
              "description": "改什么（具体到 action / 字段 / 方法）",
              "actions_affected": ["create", "list"]
            }
          ]
        }

        regenerate 模式时：
        {
          "mode": "regenerate",
          "scope": "backend",
          "reason": "...",
          "backend_changes": [
            {
              "file": "chat_api.java",
              "description": "保留 chat / history，新增 chat_stream（流式）",
              "actions_affected": ["chat_stream"]
            }
          ]
        }

        规则：
        1. mode 只能是 local / regenerate
        2. scope 只能是 backend
        3. backend_changes[].file 必须是"后端文件摘要"里列出的实际文件名
        4. description 要具体到"改哪个方法 / 哪个字段"
        5. actions_affected 是受影响的 action 名列表
        6. 只输出 JSON，不要代码块标记，不要解释
        """;

    // ============================================================
    // fix_backend prompt
    // ============================================================

    private static final String FIX_BACKEND_SYSTEM = """
        你是 Java 后端工程师。你的唯一任务：按修复需求修改现有 AICP 插件代码。

        ============================================================
        核心原则（必须遵守）
        ============================================================

        1. 默认保持原有功能和逻辑不变
        2. 只修改与修复需求直接相关的部分
        3. 不要擅自优化、重构、或改变未提及的功能
        4. 不要改变方法签名、返回值格式，除非需求明确要求
        5. 输出完整 Java 代码，不要截断、省略、或用省略号

        ============================================================
        AICP 插件签名（必须保持）
        ============================================================

        public class 类名 implements Plugin {
            @Override
            public Envelop execute(Envelop envelop, Agent agent) {
                // ...
            }
        }

        注意：
        - 不写 package 声明（默认包）
        - 类名 = 文件名（不含 .java）
        - 类名必须和原文件完全一致
        - 顶部保留原有 /** */ 注释

        ============================================================
        禁止事项
        ============================================================

        - 禁止 HTTP server（HttpServer / ServerSocket）
        - 禁止硬编码项目名
        - 禁止裸 return
        - 禁止 System.out.println
        - 禁止空 catch
        - 禁止在 execute 内部 import
        - 禁止自指 receiver
        - 禁止 import 未通过 @DEPS 声明的第三方库

        ============================================================
        返回格式
        ============================================================

        成功：envelop.payload = Map.of("ok", true, "data", ...);
        失败：envelop.payload = Map.of("ok", false, "error", "具体原因");

        ============================================================
        输出格式
        ============================================================

        只输出下面这一块，不要有其他文字，不要用 markdown 代码块包裹：

        === PLUGIN: 文件名.java ===
        （这里直接放完整的 Java 源码）
        === END ===

        规则：
        - 必须输出完整代码，不要截断
        - 不要加 markdown 代码块标记
        - 不要加任何解释文字

        ============================================================
        输出前自检
        ============================================================

        - 类名 = 文件名
        - 保留了原有所有 action
        - 只改了与修复需求相关的部分
        - 未改变其他 action 的行为
        - 每个分支都 return envelop
        - 没有空 catch
        - 没有 System.out.println
        - 输出完整代码
        """;

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "fix";

        if (!"fix".equals(action)) {
            envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
            return envelop;
        }

        String target = str(envelop.payload.get("target")).trim();
        String issue = str(envelop.payload.get("issue")).trim();
        if (issue.isEmpty()) {
            issue = str(envelop.payload.get("requirement")).trim();
        }

        if (target.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 target 参数");
            return envelop;
        }
        if (issue.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 issue 参数");
            return envelop;
        }

        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            envelop.payload = Map.of("ok", false, "error", "LLM 未配置");
            return envelop;
        }

        String sessionId = str(envelop.meta.get("session_id"));
        if (sessionId.isEmpty()) sessionId = str(envelop.payload.get("session_id"));
        if (sessionId.isEmpty()) sessionId = "default";

        // 1. 解析项目名
        String projectName = parseProjectName(target);
        if (projectName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "无法解析项目名: " + target);
            return envelop;
        }
        if (projectName.contains("..") || projectName.contains("/") || projectName.contains("\\")) {
            envelop.payload = Map.of("ok", false, "error", "项目名含非法字符");
            return envelop;
        }

        // ============================================================
        // 2. ★ 让 LLM 判断 scope
        // ============================================================
        String scope = callScopeJudge(llm, projectName, issue);
        pushProgress(agent, sessionId, "executing", "修复范围：" + scope);

        if ("frontend".equals(scope)) {
            return handleFrontendFix(envelop, agent, sessionId, projectName, issue);
        }

        // ============================================================
        // 3. 后端修复
        // ============================================================
        return handleBackendFix(envelop, agent, llm, sessionId, projectName, target, issue);
    }

    // ============================================================
    // ★ LLM 判断 scope
    // ============================================================

    private String callScopeJudge(LLM llm, String projectName, String issue) {
        String prompt = "项目名：" + projectName + "\n\n"
            + "修复需求：\n" + issue;

        try {
            Map<String, Object> result = llm.chatJson(List.of(
                Map.of("role", "system", "content", SCOPE_JUDGE_SYSTEM),
                Map.of("role", "user", "content", prompt)
            ));

            if (result == null) {
                System.err.println("[fix_tool] scope judge returned null, default backend");
                return "backend";
            }

            String scope = str(result.get("scope")).trim().toLowerCase();
            if ("frontend".equals(scope)) return "frontend";
            if ("backend".equals(scope)) return "backend";

            System.err.println("[fix_tool] scope judge returned unknown: " + scope);
            return "backend";
        } catch (Throwable t) {
            System.err.println("[fix_tool] scope judge failed: " + t);
            return "backend";
        }
    }

    // ============================================================
    // 前端修复
    // ============================================================

    private Envelop handleFrontendFix(Envelop envelop, Agent agent, String sessionId,
                                       String projectName, String issue) {
        pushProgress(agent, sessionId, "executing", "识别为前端修复，转交前端 Agent...");

        String contract = buildBackendContract(projectName);

        Envelop feEnv = new Envelop("builtins/tools/fix_tool", "builtins/agents/frontend_agent");
        feEnv.payload.put("action", "fix");
        feEnv.payload.put("project_name", projectName);
        feEnv.payload.put("description", issue);
        feEnv.payload.put("backend_contract", contract);
        feEnv.meta.put("session_id", sessionId);

        try {
            Envelop feResult = agent.call(feEnv);
            if (feResult != null && Boolean.TRUE.equals(feResult.payload.get("ok"))) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("project_name", projectName);
                data.put("mode", "frontend");
                data.put("scope", "frontend");
                Object innerData = feResult.payload.get("data");
                if (innerData instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> im = (Map<String, Object>) m;
                    data.putAll(im);
                }
                data.put("all_loaded", true);
                data.put("frontend_ready", true);
                data.put("frontend_url", "/" + projectName + "/");
                data.put("status", "complete");

                StringBuilder msg = new StringBuilder();
                msg.append("✅ 前端修复完成：").append(projectName).append("\n\n");
                msg.append("🌐 访问地址：http://127.0.0.1:9000/").append(projectName).append("/\n");
                msg.append("   刷新浏览器即可看到修改。\n");
                msg.append("\n⚠️ 不要再创建新 action，不要再改后端。前端已就绪。\n");
                msg.append("   直接告诉用户：刷新 /").append(projectName).append("/ 即可看到修改。\n");

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ok", true);
                result.put("data", data);
                result.put("message", msg.toString());
                envelop.payload = result;
                return envelop;
            } else {
                String err = feResult != null ? str(feResult.payload.get("error")) : "无响应";
                envelop.payload = Map.of("ok", false, "error", "前端修复失败: " + err);
                return envelop;
            }
        } catch (Throwable t) {
            envelop.payload = Map.of("ok", false, "error", "前端修复异常: " + t.getMessage());
            return envelop;
        }
    }

    /** 从项目已有的后端源码构建契约（前端修复时用） */
    private String buildBackendContract(String projectName) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目名：").append(projectName).append("\n");
        sb.append("路由前缀：applications/").append(projectName).append("\n\n");

        List<Path> backendFiles = findBackendFiles(projectName);
        for (Path f : backendFiles) {
            try {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                String fileName = f.getFileName().toString();
                String pluginName = fileName.replace(".java", "");

                sb.append("### ").append(pluginName).append("\n");
                sb.append("调用：POST /api/applications/").append(projectName)
                  .append("/").append(pluginName).append("\n");

                Matcher m = Pattern.compile("case\\s+\"(\\w+)\"\\s*:").matcher(code);
                List<String> actions = new ArrayList<>();
                while (m.find()) actions.add(m.group(1));
                if (!actions.isEmpty()) {
                    sb.append("actions：").append(String.join(", ", actions)).append("\n");
                }

                Matcher p = Pattern.compile("payload\\.get\\(\"(\\w+)\"\\)").matcher(code);
                Set<String> params = new LinkedHashSet<>();
                while (p.find()) params.add(p.group(1));
                if (!params.isEmpty()) {
                    sb.append("参数：").append(String.join(", ", params)).append("\n");
                }

                Matcher r1 = Pattern.compile("data\\.put\\(\"(\\w+)\"").matcher(code);
                Set<String> outFields = new LinkedHashSet<>();
                while (r1.find()) outFields.add(r1.group(1));
                Matcher r2 = Pattern.compile("Map\\.of\\(\"(\\w+)\"\\s*,").matcher(code);
                while (r2.find()) {
                    String fld = r2.group(1);
                    if (!fld.equals("ok") && !fld.equals("data") && !fld.equals("error")
                        && !fld.equals("message")) {
                        outFields.add(fld);
                    }
                }
                if (!outFields.isEmpty()) {
                    sb.append("返回字段：").append(String.join(", ", outFields)).append("\n");
                }

                sb.append("\n");
            } catch (Exception ignored) {}
        }

        return sb.toString();
    }

    // ============================================================
    // 后端修复
    // ============================================================

    private Envelop handleBackendFix(Envelop envelop, Agent agent, LLM llm,
                                      String sessionId, String projectName,
                                      String target, String issue) {
        List<Path> backendFiles = findBackendFiles(projectName);
        if (backendFiles.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "项目不存在或无 .java 文件: " + projectName);
            return envelop;
        }

        List<Map<String, String>> fileInfos = new ArrayList<>();
        for (Path f : backendFiles) {
            try {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                fileInfos.add(Map.of(
                    "fileName", f.getFileName().toString(),
                    "summary", extractSummary(code)
                ));
            } catch (IOException e) {
                fileInfos.add(Map.of(
                    "fileName", f.getFileName().toString(),
                    "summary", "(读取失败)"
                ));
            }
        }

        String backendSource = collectBackendSource(backendFiles);

        pushProgress(agent, sessionId, "executing", "分析修复方案...");

        Map<String, Object> design;
        try {
            design = callFixDesign(llm, projectName, issue, fileInfos, backendSource);
        } catch (Throwable t) {
            envelop.payload = Map.of("ok", false, "error", "修复方案设计失败: " + t.getMessage());
            return envelop;
        }

        String mode = str(design.getOrDefault("mode", "regenerate"));
        String scope = str(design.getOrDefault("scope", "backend"));
        String reason = str(design.get("reason"));

        Object changesObj = design.get("backend_changes");
        List<Map<String, Object>> backendChanges = new ArrayList<>();
        if (changesObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    backendChanges.add(mm);
                }
            }
        }

        pushProgress(agent, sessionId, "done",
            "修复方案确定：mode=" + mode + "（" + reason + "）");

        List<String> writtenFiles = new ArrayList<>();

        if ("regenerate".equals(mode)) {
            int idx = 0;
            for (Path file : backendFiles) {
                idx++;
                String fileName = file.getFileName().toString();
                String pluginName = fileName.replace(".java", "");

                String currentCode;
                try {
                    currentCode = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    envelop.payload = Map.of("ok", false,
                        "error", "读取文件失败: " + fileName + ": " + e.getMessage());
                    return envelop;
                }

                String changeDesc = "";
                for (Map<String, Object> c : backendChanges) {
                    if (fileName.equals(str(c.get("file")))) {
                        changeDesc = str(c.get("description"));
                        break;
                    }
                }
                if (changeDesc.isEmpty()) changeDesc = issue;

                pushProgress(agent, sessionId, "executing",
                    "重生成 " + fileName + "（" + idx + "/" + backendFiles.size() + "）...");

                pushCode(agent, sessionId,
                    "\n\n// ══════════════════════════════════════════\n" +
                    "// " + fileName + "（重生成）\n" +
                    "// " + changeDesc + "\n" +
                    "// ══════════════════════════════════════════\n\n");

                String newCode;
                try {
                    newCode = callGenerateBackend(llm, projectName, pluginName, issue,
                        changeDesc, currentCode, agent, sessionId);
                    newCode = enforceClassName(newCode, pluginName);
                    newCode = newCode.replace("{project}", projectName);
                } catch (Throwable t) {
                    envelop.payload = Map.of("ok", false,
                        "error", "重生成失败 (" + fileName + "): " + t.getMessage());
                    return envelop;
                }

                try {
                    Files.writeString(file, newCode, StandardCharsets.UTF_8);
                    writtenFiles.add(relPath(file));
                } catch (IOException e) {
                    envelop.payload = Map.of("ok", false,
                        "error", "写入失败 (" + fileName + "): " + e.getMessage());
                    return envelop;
                }

                pushProgress(agent, sessionId, "done",
                    fileName + " 重生成完成（" + newCode.length() + " 字）");
            }
        } else {
            int idx = 0;
            for (Map<String, Object> change : backendChanges) {
                idx++;
                String fileName = str(change.get("file"));
                if (fileName.isEmpty()) continue;

                Path file = Path.of(PROJECTS_DIR, projectName, fileName);
                if (!Files.exists(file)) {
                    envelop.payload = Map.of("ok", false, "error", "后端文件不存在: " + fileName);
                    return envelop;
                }

                String pluginName = fileName.replace(".java", "");

                String currentCode;
                try {
                    currentCode = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    envelop.payload = Map.of("ok", false,
                        "error", "读取文件失败: " + fileName + ": " + e.getMessage());
                    return envelop;
                }

                pushProgress(agent, sessionId, "executing",
                    "修改 " + fileName + "（" + idx + "/" + backendChanges.size() + "）...");

                pushCode(agent, sessionId,
                    "\n\n// ══════════════════════════════════════════\n" +
                    "// " + fileName + "（局部修复）\n" +
                    "// " + str(change.get("description")) + "\n" +
                    "// ══════════════════════════════════════════\n\n");

                String newCode;
                try {
                    newCode = callFixBackend(llm, projectName, fileName, issue,
                        currentCode, change, agent, sessionId);
                    newCode = enforceClassName(newCode, pluginName);
                    newCode = newCode.replace("{project}", projectName);
                } catch (Throwable t) {
                    envelop.payload = Map.of("ok", false,
                        "error", "修复失败 (" + fileName + "): " + t.getMessage());
                    return envelop;
                }

                try {
                    Files.writeString(file, newCode, StandardCharsets.UTF_8);
                    writtenFiles.add(relPath(file));
                } catch (IOException e) {
                    envelop.payload = Map.of("ok", false,
                        "error", "写入失败 (" + fileName + "): " + e.getMessage());
                    return envelop;
                }

                pushProgress(agent, sessionId, "done",
                    fileName + " 修复完成（" + newCode.length() + " 字）");
            }
        }

        pushProgress(agent, sessionId, "executing", "等待热加载编译...");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        List<String> loaded = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();

        for (Path file : backendFiles) {
            String receiver = "applications/" + projectName + "/" + file.getFileName().toString().replace(".java", "");
            if (Plugins.INSTANCE.containsKey(receiver)) {
                loaded.add(receiver);
            } else {
                notLoaded.add(receiver);
            }
        }

        boolean allLoaded = notLoaded.isEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_name", projectName);
        data.put("mode", mode);
        data.put("scope", scope);
        data.put("reason", reason);
        data.put("files", writtenFiles);
        data.put("backend_changes", backendChanges);
        data.put("loaded", loaded);
        data.put("not_loaded", notLoaded);
        data.put("all_loaded", allLoaded);
        data.put("status", allLoaded ? "complete" : "partial");

        StringBuilder msg = new StringBuilder();
        msg.append("✅ 后端修复完成：").append(projectName).append("\n\n");
        msg.append("模式：").append(mode).append("\n");
        msg.append("原因：").append(reason).append("\n");
        msg.append("改动文件（").append(writtenFiles.size()).append(" 个）：\n");
        for (String f : writtenFiles) {
            msg.append("  - ").append(f).append("\n");
        }

        if (allLoaded) {
            msg.append("\n📦 插件已重新加载，可继续测试。\n");
        } else {
            msg.append("\n⚠️ 部分插件未加载，请检查编译错误：\n");
            for (String r : notLoaded) {
                msg.append("  - ").append(r).append("\n");
            }
        }

        msg.append("\n🔴 你下一步应该做的：\n");
        msg.append("  重新查询契约，然后测试新行为是否符合需求。\n");
        msg.append("  如果测试通过 → 直接回复用户结果。\n");
        msg.append("  不要重复调用 fix_tool。\n");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("data", data);
        result.put("message", msg.toString());

        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // LLM 调用
    // ============================================================

    private Map<String, Object> callFixDesign(
        LLM llm,
        String projectName,
        String issue,
        List<Map<String, String>> fileInfos,
        String backendSource
    ) {
        StringBuilder filesDesc = new StringBuilder();
        for (Map<String, String> f : fileInfos) {
            String summary = f.get("summary");
            String firstLine = summary.contains("\n") ? summary.substring(0, summary.indexOf("\n")) : summary;
            filesDesc.append("- ").append(f.get("fileName")).append("：").append(firstLine).append("\n");
        }

        String prompt = "项目名：" + projectName + "\n\n"
            + "修复需求：" + issue + "\n\n"
            + "【后端文件摘要】\n" + filesDesc + "\n"
            + "【后端完整源码】\n" + backendSource + "\n\n"
            + "请判断 mode / scope / 具体改什么。输出修复方案 JSON，只输出 JSON。";

        Map<String, Object> result = llm.chatJson(List.of(
            Map.of("role", "system", "content", FIX_DESIGN_SYSTEM),
            Map.of("role", "user", "content", prompt)
        ));

        if (result == null) throw new RuntimeException("LLM 返回为空");
        if (result.get("error") != null) throw new RuntimeException(result.get("error").toString());
        if (result.get("parse_error") != null) throw new RuntimeException("JSON 解析失败: " + result.get("parse_error"));

        return result;
    }

    private String callFixBackend(
        LLM llm,
        String projectName,
        String fileName,
        String issue,
        String currentCode,
        Map<String, Object> change,
        Agent agent,
        String sessionId
    ) {
        String changeJson;
        try {
            changeJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(change);
        } catch (Exception e) {
            changeJson = change.toString();
        }

        String prompt = "项目名：" + projectName + "\n\n"
            + "后端文件名：" + fileName + "\n\n"
            + "修复需求：" + issue + "\n\n"
            + "具体修改点：\n" + changeJson + "\n\n"
            + "当前代码（完整文件，必须全量输出修改后的版本）：\n\n" + currentCode + "\n\n"
            + "请按修复需求修改代码，输出完整的新代码。\n\n"
            + "只输出 === PLUGIN: " + fileName + " === ... === END === 块。";

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", FIX_BACKEND_SYSTEM),
            Map.of("role", "user", "content", prompt)
        );

        String raw = streamLLM(llm, messages, agent, sessionId);
        if (raw.isEmpty()) throw new RuntimeException("LLM 返回为空");

        String code = extractPluginBlock(raw, fileName.replace(".java", ""));
        if (code == null || code.isEmpty()) throw new RuntimeException("无法提取 Java 代码");
        return code;
    }

    private String callGenerateBackend(
        LLM llm,
        String projectName,
        String pluginName,
        String issue,
        String changeDesc,
        String currentCode,
        Agent agent,
        String sessionId
    ) {
        String system = com.aicp.runtime.SystemPrompts.GENERATE_BACKEND_SYSTEM;

        String prompt = "项目名：" + projectName + "\n\n"
            + "插件名：" + pluginName + "\n\n"
            + "原始需求：" + issue + "\n\n"
            + "具体修改点：\n" + changeDesc + "\n\n"
            + "【★ 现有代码 — 必须保留所有功能 ★】\n\n" + currentCode + "\n\n"
            + "要求：\n"
            + "- 保留现有代码的所有 action、参数、返回值、字段名\n"
            + "- 按新需求新增 / 修改功能\n"
            + "- 不要删除未提及的 action\n\n"
            + "只输出 === PLUGIN: " + pluginName + ".java === ... === END === 块。";

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", prompt)
        );

        String raw = streamLLM(llm, messages, agent, sessionId);
        if (raw.isEmpty()) throw new RuntimeException("LLM 返回为空");

        String code = extractPluginBlock(raw, pluginName);
        if (code == null || code.isEmpty()) throw new RuntimeException("无法提取 Java 代码");
        return code;
    }

    private String streamLLM(LLM llm, List<Map<String, String>> messages,
                              Agent agent, String sessionId) {
        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean hasError = new AtomicBoolean(false);
        final AtomicReference<String> errorMsg = new AtomicReference<>("");

        llm.chatStream(messages, null, null, (delta, done, error) -> {
            if (delta != null && !delta.isEmpty()) {
                buffer.append(delta);
                pushCode(agent, sessionId, delta);
            }
            if (done && error != null && !error.isEmpty()) {
                hasError.set(true);
                errorMsg.set(error);
            }
        });

        if (hasError.get()) {
            throw new RuntimeException("LLM 流式失败: " + errorMsg.get());
        }

        return buffer.toString();
    }

    // ============================================================
    // WS 推送
    // ============================================================

    private void pushCode(Agent agent, String sessionId, String chunk) {
        try {
            Envelop env = new Envelop("builtins/tools/fix_tool", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "code_stream");
            data.put("chunk", chunk);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Throwable ignored) {}
    }

    private void pushProgress(Agent agent, String sessionId, String step, String msg) {
        try {
            Envelop env = new Envelop("builtins/tools/fix_tool", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "progress");
            data.put("step", step);
            data.put("msg", msg);
            data.put("message", msg);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // 项目文件查找
    // ============================================================

    private String parseProjectName(String target) {
        String t = target.trim();
        if (t.contains("applications/")) {
            String after = t.substring(t.lastIndexOf("applications/") + "applications/".length());
            int slash = after.indexOf('/');
            return slash >= 0 ? after.substring(0, slash) : after;
        }
        if (t.startsWith("www/")) {
            String after = t.substring(4);
            int slash = after.indexOf('/');
            return slash >= 0 ? after.substring(0, slash) : after;
        }
        return t;
    }

    private List<Path> findBackendFiles(String projectName) {
        List<Path> result = new ArrayList<>();
        Path dir = Path.of(PROJECTS_DIR, projectName);
        if (!Files.exists(dir)) return result;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".java")) return false;
                    if (name.endsWith(".d.java")) return false;
                    if (name.contains(".test.")) return false;
                    if (name.contains(".spec.")) return false;
                    return true;
                })
                .sorted()
                .forEach(result::add);
        } catch (IOException ignored) {}

        return result;
    }

    private String collectBackendSource(List<Path> backendFiles) {
        StringBuilder sb = new StringBuilder();
        for (Path f : backendFiles) {
            try {
                String code = Files.readString(f, StandardCharsets.UTF_8);
                sb.append("=== ").append(f.getFileName().toString()).append(" ===\n");
                sb.append(code).append("\n\n");
            } catch (IOException ignored) {}
        }

        String combined = sb.toString();
        if (combined.length() > MAX_BACKEND_SOURCE_CHARS) {
            combined = combined.substring(0, MAX_BACKEND_SOURCE_CHARS) + "\n\n... (后端源码过长，已截断)";
        }
        return combined;
    }

    private String extractSummary(String code) {
        List<String> parts = new ArrayList<>();

        Pattern commentPattern = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/");
        Matcher m = commentPattern.matcher(code);
        if (m.find()) {
            String comment = m.group(1);
            String[] lines = comment.split("\n");
            List<String> cleanLines = new ArrayList<>();
            for (String line : lines) {
                String cleaned = line.replaceAll("^\\s*\\*\\s?", "").trim();
                if (!cleaned.isEmpty()) cleanLines.add(cleaned);
                if (cleanLines.size() >= 3) break;
            }
            if (!cleanLines.isEmpty()) {
                parts.add("注释: " + String.join(" | ", cleanLines));
            }
        }

        List<String> actions = new ArrayList<>();
        Pattern casePattern = Pattern.compile("case\\s+\"([^\"]+)\"\\s*:");
        Matcher cm = casePattern.matcher(code);
        while (cm.find()) {
            actions.add(cm.group(1));
        }
        if (!actions.isEmpty()) {
            parts.add("action: " + String.join(", ", actions));
        }

        if (parts.isEmpty()) {
            String[] lines = code.split("\n");
            int n = Math.min(10, lines.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(lines[i]).append("\n");
            return sb.toString();
        }

        return String.join("\n", parts);
    }

    private String relPath(Path absPath) {
        try {
            return Path.of(System.getProperty("user.dir")).relativize(absPath).toString().replace("\\", "/");
        } catch (Exception e) {
            return absPath.toString();
        }
    }

    // ============================================================
    // 类名 / 占位符修正
    // ============================================================

    private String enforceClassName(String code, String pluginName) {
        if (code == null || code.isEmpty()) return code;

        Pattern p = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
        Matcher m = p.matcher(code);
        if (m.find()) {
            String oldClassName = m.group(1);
            if (!oldClassName.equals(pluginName)) {
                code = code.replaceAll(
                    "\\b" + Pattern.quote(oldClassName) + "\\b",
                    Matcher.quoteReplacement(pluginName)
                );
            }
        }
        return code;
    }

    // ============================================================
    // 提取代码块
    // ============================================================

    private String extractPluginBlock(String raw, String pluginName) {
        Pattern p1 = Pattern.compile(
            "=== PLUGIN:\\s*[^=]*" + Pattern.quote(pluginName) + "[^=]*===\\s*\\n([\\s\\S]*?)=== END ===",
            Pattern.CASE_INSENSITIVE
        );
        Matcher m1 = p1.matcher(raw);
        if (m1.find()) return cleanup(m1.group(1));

        Pattern p2 = Pattern.compile("=== PLUGIN:\\s*[^=]+===\\s*\\n([\\s\\S]*?)=== END ===");
        Matcher m2 = p2.matcher(raw);
        if (m2.find()) return cleanup(m2.group(1));

        Pattern p3 = Pattern.compile("```java\\s*\\n([\\s\\S]*?)```");
        Matcher m3 = p3.matcher(raw);
        if (m3.find()) return cleanup(m3.group(1));

        return null;
    }

    private String cleanup(String code) {
        if (code == null) return null;
        String s = code.trim();

        if (s.startsWith("```java")) s = s.substring(7).trim();
        else if (s.startsWith("```")) s = s.substring(3).trim();
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3).trim();

        return s;
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
