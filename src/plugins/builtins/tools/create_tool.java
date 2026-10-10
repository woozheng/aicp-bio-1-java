import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;
import com.aicp.runtime.LLM;
import com.aicp.runtime.SystemPrompts;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * builtins/tools/create_tool — 创建新应用（Java 版）
 *
 * 流程：
 *   1. design → 架构 JSON（LLM）+ need_frontend 判断
 *   2. 逐个 generate_backend → Java 代码（LLM，流式推 code_stream）
 *   3. ★ 每个插件写入后立即编译检查，失败则带错误重生成一次
 *   4. ★ 如果 need_frontend=true → 调 frontend_agent.create（附源码片段）
 *   5. enforceClassName：强制类名 = 文件名
 *   6. 写文件
 *   7. 等 2 秒，查 Plugins.INSTANCE 验证
 */
public class create_tool implements Plugin {

    private static final String PROJECTS_DIR = "src/plugins/applications";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============================================================
    // design prompt
    // ============================================================

    private static final String DESIGN_SYSTEM = """
        你是一个架构设计专家。你的任务：根据用户需求，设计插件架构。

        输出严格 JSON，不要有任何其他文字：

        {
          "project_name": "英文小写下划线，如 task_board",
          "need_frontend": false,
          "frontend_description": "",
          "plugins": [
            {
              "name": "插件文件名（不含 .java，全小写），如 task_api",
              "description": "这个插件做什么（一句话）",
              "actions": [
                {
                  "name": "action 名（如 create / list / delete）",
                  "description": "这个 action 做什么",
                  "params": ["参数名1", "参数名2"]
                }
              ]
            }
          ]
        }

        ============================================================
        判断规则：什么时候需要前端（need_frontend）
        ============================================================
        需要前端（need_frontend=true）：
        - 用户提到：页面、界面、前端、可视化、展示、表格、图表、表单、点击、按钮、UI
        - 用户说"做个 XXX 应用 / 工具 / 系统 / 清单 / 管理器"，隐含需要交互
        - 用户明确要"能看到 / 能操作 / 能访问"

        不需要前端（need_frontend=false）：
        - 用户只提到：API、插件、后台服务、纯计算、命令行工具
        - 用户说"写个插件 / 提供 API / 后台处理"
        - 不确定时：need_frontend=false（保守）

        如果 need_frontend=true，必须填 frontend_description：
        - 一句话描述主页面要展示什么、支持什么操作
        - 例："任务列表页面，支持添加、删除、标记完成"

        ============================================================
        其他规则
        ============================================================
        1. project_name 必须是英文小写 + 下划线
        2. plugin.name 必须全小写（如 fibonacci，不要 Fibonacci）
        3. plugins 至少 1 个，最多 3 个
        4. 尽量把功能集中，不要拆太碎
        5. 纯后端插件，不考虑前端代码（前端由 frontend_agent 负责）
        6. ★ 不要把"WebSocket 推送"设计成独立插件：
           - 推送逻辑直接写在业务插件的 action 里
           - 业务插件增删改后，直接调 os/_websocket 推送：
             agent.call(new Envelop(
               "applications/{project}/xxx_api",
               "os/_websocket",
               Map.of("action", "push",
                      "channel_id", "pa_" + sessionId,
                      "data", Map.of("type", "xxx_change"))
             ));
           - 禁止生成 {project}_push.java 这种独立推送插件（会成为死代码）
        7. ★ actions[].params 必须列全该 action 支持的所有参数：
           - list 类 action 必须考虑：分页（page, page_size）、筛选（category, type, year, month）
           - 需求里提到的每个筛选维度，都要在 params 里列出来
           - 前端会按 params 生成筛选 UI，漏了就对不上
           - 例：list_bills 的 params 应该是
             ["page", "page_size", "category", "type", "year", "month"]
           - 例：create_bill 的 params 应该是
             ["amount", "type", "category", "remark", "attachment"]
           - 例：delete_bill 的 params 应该是 ["id"]
        8. ★ actions[].params 的命名必须和需求一致：
           - 需求说"按分类筛选" → params 里必须有 "category"
           - 需求说"按月份筛选" → params 里必须有 "year" 和 "month"
           - 需求说"按类型筛选" → params 里必须有 "type"
           - 需求说"标记完成" → params 里必须有 "completed" 或 "done"
           - 禁止用同义词替换（如"分类"不能写成 "kind"、"cat"）
        """;

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "create";

        if (!"create".equals(action)) {
            envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
            return envelop;
        }

        String name = str(envelop.payload.get("name")).trim();
        String description = str(envelop.payload.get("description")).trim();

        if (name.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 name 参数");
            return envelop;
        }
        if (description.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 description 参数");
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

        // ============================================================
        // 1. design（同步）
        // ============================================================
        pushProgress(agent, sessionId, "executing", "分析需求，设计架构...");

        Map<String, Object> design;
        try {
            design = callDesign(llm, name, description);
        } catch (Exception e) {
            envelop.payload = Map.of("ok", false, "error", "架构设计失败: " + e.getMessage());
            return envelop;
        }

        String projectName = str(design.get("project_name"));
        if (projectName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "design 返回缺少 project_name");
            return envelop;
        }

        Object pluginsObj = design.get("plugins");
        if (!(pluginsObj instanceof List<?> pluginsList) || pluginsList.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "design 返回缺少 plugins");
            return envelop;
        }

        boolean needFrontend = Boolean.TRUE.equals(design.get("need_frontend"));
        String frontendDesc = str(design.get("frontend_description"));

        pushProgress(agent, sessionId, "done",
            "架构设计完成：" + projectName + "（" + pluginsList.size() + " 个插件"
                + (needFrontend ? "，需要前端" : "") + "）");

        // ============================================================
        // 2. 逐个 generate_backend（流式 + 编译检查 + 失败重生成）
        // ============================================================
        List<String> writtenFiles = new ArrayList<>();
        List<String> receivers = new ArrayList<>();

        int pluginIdx = 0;
        int pluginTotal = pluginsList.size();

        for (Object pluginSpecObj : pluginsList) {
            pluginIdx++;
            if (!(pluginSpecObj instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> pluginSpec = (Map<String, Object>) pluginSpecObj;

            String pluginName = str(pluginSpec.get("name")).trim();
            if (pluginName.isEmpty()) continue;

            String pluginDesc = str(pluginSpec.get("description"));

            pushProgress(agent, sessionId, "executing",
                "生成 " + pluginName + ".java（" + pluginIdx + "/" + pluginTotal + "）...");

            pushCode(agent, sessionId,
                "\n\n// ══════════════════════════════════════════\n" +
                "// " + pluginName + ".java（" + pluginIdx + "/" + pluginTotal + "）\n" +
                "// " + pluginDesc + "\n" +
                "// ══════════════════════════════════════════\n\n");

            String code;
            try {
                code = callGenerateBackend(llm, projectName, pluginSpec, pluginDesc, agent, sessionId);
                code = enforceClassName(code, pluginName);
                code = code.replace("{project}", projectName);
            } catch (Exception e) {
                envelop.payload = Map.of("ok", false,
                    "error", "生成插件失败 (" + pluginName + "): " + e.getMessage());
                return envelop;
            }

            String relPath = "src/plugins/applications/" + projectName + "/" + pluginName + ".java";
            String receiver = "applications/" + projectName + "/" + pluginName;

            try {
                Path filePath = Path.of(relPath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, code, StandardCharsets.UTF_8);

                // ★ 编译检查
                String compileError = compileCheck(filePath);
                if (compileError != null) {
                    pushProgress(agent, sessionId, "executing",
                        "⚠️ " + pluginName + ".java 编译失败，带错误重生成...");

                    String retryCode;
                    try {
                        retryCode = callGenerateBackendWithError(
                            llm, projectName, pluginSpec, pluginDesc,
                            code, compileError, agent, sessionId);
                        retryCode = enforceClassName(retryCode, pluginName);
                        retryCode = retryCode.replace("{project}", projectName);
                    } catch (Exception e) {
                        pushProgress(agent, sessionId, "error",
                            "❌ " + pluginName + ".java 重生成失败：" + e.getMessage());
                        // 保留第一次的代码（虽然编译失败，至少文件在）
                        writtenFiles.add(relPath);
                        receivers.add(receiver);
                        continue;
                    }

                    Files.writeString(filePath, retryCode, StandardCharsets.UTF_8);

                    // ★ 再检查一次
                    String err2 = compileCheck(filePath);
                    if (err2 != null) {
                        pushProgress(agent, sessionId, "error",
                            "❌ " + pluginName + ".java 二次编译仍失败：" + err2);
                    } else {
                        pushProgress(agent, sessionId, "done",
                            "✅ " + pluginName + ".java 二次编译通过");
                    }
                    code = retryCode;
                }

                writtenFiles.add(relPath);
                receivers.add(receiver);
            } catch (IOException e) {
                envelop.payload = Map.of("ok", false,
                    "error", "写入插件失败 (" + pluginName + "): " + e.getMessage());
                return envelop;
            }

            pushProgress(agent, sessionId, "done",
                pluginName + ".java 写入完成（" + code.length() + " 字）");
        }

        // ============================================================
        // 3. ★ 前端生成
        // ============================================================
        String frontendFile = "";
        boolean frontendOk = false;

        if (needFrontend) {
            pushProgress(agent, sessionId, "executing", "生成前端页面...");

            String contract = buildBackendContract(projectName, writtenFiles);

            Envelop feEnv = new Envelop("builtins/tools/create_tool", "builtins/agents/frontend_agent");
            feEnv.payload.put("action", "create");
            feEnv.payload.put("project_name", projectName);
            feEnv.payload.put("description",
                frontendDesc.isEmpty() ? description : frontendDesc);
            feEnv.payload.put("backend_contract", contract);
            feEnv.meta.put("session_id", sessionId);

            try {
                Envelop feResult = agent.call(feEnv);
                if (feResult != null && Boolean.TRUE.equals(feResult.payload.get("ok"))) {
                    frontendOk = true;
                    Object dataObj = feResult.payload.get("data");
                    if (dataObj instanceof Map<?, ?> m) {
                        frontendFile = str(m.get("file"));
                    }
                    pushProgress(agent, sessionId, "done",
                        "前端页面生成完成：" + str(feResult.payload.get("message")));
                } else {
                    String feErr = feResult != null ? str(feResult.payload.get("error")) : "无响应";
                    pushProgress(agent, sessionId, "error",
                        "前端生成失败：" + feErr + "（后端已生成，可后续重试）");
                }
            } catch (Exception e) {
                pushProgress(agent, sessionId, "error",
                    "前端生成异常：" + e.getMessage() + "（后端已生成，可后续重试）");
            }
        }

        // ============================================================
        // 4. 等待加载（轮询，最多 15 秒）
        // ============================================================
        pushProgress(agent, sessionId, "executing", "等待热加载编译...");

        List<String> loaded = new ArrayList<>();
        List<String> notLoaded = new ArrayList<>();

        long deadline = System.currentTimeMillis() + 15000;
        int checkCount = 0;
        while (System.currentTimeMillis() < deadline) {
            loaded.clear();
            notLoaded.clear();
            for (String receiver : receivers) {
                if (Plugins.INSTANCE.containsKey(receiver)) {
                    loaded.add(receiver);
                } else {
                    notLoaded.add(receiver);
                }
            }
            if (notLoaded.isEmpty()) break;

            checkCount++;
            if (checkCount % 4 == 0) {   // 每 2 秒推一次进度
                pushProgress(agent, sessionId, "executing",
                    "等待编译... (" + loaded.size() + "/" + receivers.size() + " 已加载)");
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean allLoaded = notLoaded.isEmpty();


        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_name", projectName);
        data.put("backend_plugins", receivers);
        data.put("backend_count", receivers.size());
        data.put("backend_loaded", allLoaded);
        data.put("need_frontend", needFrontend);

        if (needFrontend && frontendOk) {
            data.put("frontend_url", "/" + projectName + "/");
            data.put("frontend_file", frontendFile);
            data.put("frontend_ready", true);
        } else if (needFrontend) {
            data.put("frontend_ready", false);
            data.put("frontend_error", "前端生成失败");
        } else {
            data.put("frontend_ready", false);
        }
        String status;
        if (needFrontend) {
            status = frontendOk ? "complete" : "backend_only";
        } else {
            status = "complete";
        }
        data.put("status", status);

        StringBuilder msg = new StringBuilder();

        msg.append("✅ 项目创建完成：").append(projectName).append("\n\n");

        msg.append("📦 后端插件（").append(receivers.size()).append(" 个）：\n");
        for (String r : receivers) {
            msg.append("  - ").append(r);
            if (!loaded.contains(r)) msg.append(" ⚠️未加载");
            msg.append("\n");
        }

        if (needFrontend && frontendOk) {
            msg.append("\n🌐 前端页面：已生成\n");
            msg.append("   访问地址：http://127.0.0.1:9000/").append(projectName).append("/\n");
            msg.append("   浏览器直接打开即可使用。\n");
            msg.append("\n⚠️ 不要再创建 page action，也不要再改后端。前端已就绪。\n");
            msg.append("   直接告诉用户：访问 /").append(projectName).append("/ 即可使用。\n");
        } else if (needFrontend) {
            msg.append("\n⚠️ 前端生成失败，后端已就绪，可稍后单独重试。\n");
        } else {
            msg.append("\n（本项目不需要前端）\n");
            msg.append("   后端已就绪，可用 curl 或 API 调用。\n");
        }

        if (!allLoaded) {
            msg.append("\n⚠️ 部分插件未加载，请检查编译错误。\n");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("data", data);
        result.put("message", msg.toString());

        envelop.payload = result;
        return envelop;
    }

    // ============================================================
    // ★ 编译检查
    // ============================================================

    private String compileCheck(Path javaFile) {
        try {
            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return "No JavaCompiler available";

            Path outDir = javaFile.getParent();

            List<String> cp = new ArrayList<>();
            cp.add(System.getProperty("java.class.path"));
            Path libsDir = Path.of("src/plugins/libs");
            if (Files.exists(libsDir)) {
                try (var stream = Files.list(libsDir)) {
                    stream.filter(p -> p.toString().endsWith(".jar"))
                          .forEach(p -> cp.add(p.toString()));
                } catch (IOException ignored) {}
            }

            // ★ 用 ByteArrayOutputStream 接错误输出
            java.io.ByteArrayOutputStream errStream = new java.io.ByteArrayOutputStream();

            List<String> args = new ArrayList<>();
            args.add("-d");
            args.add(outDir.toString());
            args.add("-classpath");
            args.add(String.join(File.pathSeparator, cp));
            args.add(javaFile.toString());

            int result = compiler.run(null, null, errStream, args.toArray(new String[0]));

            if (result != 0) {
                String err = errStream.toString(java.nio.charset.StandardCharsets.UTF_8);
                if (err.length() > 2000) err = err.substring(0, 2000) + "...(截断)";
                return err;
            }
            return null;
        } catch (Exception e) {
            return "编译检查异常: " + e.getMessage();
        }
    }

    // ============================================================
    // 后端契约构建（附源码片段）
    // ============================================================

    private String buildBackendContract(String projectName, List<String> writtenFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目名：").append(projectName).append("\n");
        sb.append("路由前缀：applications/").append(projectName).append("\n\n");
        sb.append("以下所有后端 API 插件的完整源代码。\n");
        sb.append("你必须从中提取每个 action 的名称、参数、返回结构（包括嵌套字段）。\n");
        sb.append("所有字段名必须与代码中完全一致，禁止自己编造。\n\n");

        int totalChars = 0;
        final int MAX_CHARS = 30000;

        for (String relPath : writtenFiles) {
            try {
                String code = Files.readString(Path.of(relPath), StandardCharsets.UTF_8);
                String fileName = Path.of(relPath).getFileName().toString();
                String pluginName = fileName.replace(".java", "");

                sb.append("═══════════════════════════════════════\n");
                sb.append("【").append(pluginName).append(".java】\n");
                sb.append("调用：POST /api/applications/").append(projectName)
                  .append("/").append(pluginName).append("\n");
                sb.append("body：{\"payload\": {\"action\": \"xxx\", ...params}}\n");
                sb.append("═══════════════════════════════════════\n\n");

                // ★ 完整源码（不截断单个文件）
                sb.append(code).append("\n\n");

                totalChars += code.length();
                if (totalChars > MAX_CHARS) {
                    sb.append("\n// ... (后端源码总长超过 ")
                      .append(MAX_CHARS)
                      .append(" 字符，已截断)\n");
                    break;
                }
            } catch (Exception ignored) {}
        }

        return sb.toString();
    }

    // ============================================================
    // 强制类名 = 文件名
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
    // LLM 调用
    // ============================================================

    private Map<String, Object> callDesign(LLM llm, String name, String description) {
        String prompt = "项目名：" + name + "\n\n需求：\n" + description;

        Map<String, Object> result = llm.chatJson(List.of(
            Map.of("role", "system", "content", DESIGN_SYSTEM),
            Map.of("role", "user", "content", prompt)
        ));

        if (result == null) throw new RuntimeException("LLM 返回为空");
        if (result.get("error") != null) throw new RuntimeException(result.get("error").toString());
        if (result.get("parse_error") != null) throw new RuntimeException("JSON 解析失败: " + result.get("parse_error"));

        return result;
    }

    private String callGenerateBackend(
        LLM llm,
        String projectName,
        Map<String, Object> pluginSpec,
        String pluginDesc,
        Agent agent,
        String sessionId
    ) {
        String specJson;
        try {
            specJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pluginSpec);
        } catch (Exception e) {
            specJson = pluginSpec.toString();
        }

        String prompt = "项目名：" + projectName + "\n\n"
            + "插件描述：" + pluginDesc + "\n\n"
            + "插件规格：\n" + specJson;

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", SystemPrompts.GENERATE_BACKEND_SYSTEM),
            Map.of("role", "user", "content", prompt)
        );

        String raw = streamLLM(llm, messages, agent, sessionId);
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("LLM 返回为空");
        }

        String code = extractPluginBlock(raw, str(pluginSpec.get("name")));
        if (code == null || code.isEmpty()) {
            throw new RuntimeException("无法从 LLM 输出提取 Java 代码");
        }
        return code;
    }

    // ★ 带编译错误重生成
    private String callGenerateBackendWithError(
        LLM llm,
        String projectName,
        Map<String, Object> pluginSpec,
        String pluginDesc,
        String oldCode,
        String compileError,
        Agent agent,
        String sessionId
    ) {
        String specJson;
        try {
            specJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pluginSpec);
        } catch (Exception e) {
            specJson = pluginSpec.toString();
        }

        // 限制 oldCode 长度，避免 prompt 过长
        String trimmedCode = oldCode;
        if (trimmedCode.length() > 8000) {
            trimmedCode = trimmedCode.substring(0, 8000) + "\n// ... (截断)";
        }

        String prompt = "项目名：" + projectName + "\n\n"
            + "插件描述：" + pluginDesc + "\n\n"
            + "插件规格：\n" + specJson + "\n\n"
            + "═══════════════════════════════════════\n"
            + "【上一次生成的代码编译失败，请按 Java 类型规范修正后重新生成】\n"
            + "═══════════════════════════════════════\n\n"
            + "上一次生成的代码：\n```java\n" + trimmedCode + "\n```\n\n"
            + "编译错误：\n" + compileError + "\n\n"
            + "请仔细分析错误原因（常见：Object 转 double 类型错误、Map.merge 泛型不匹配、"
            + "方法调用参数不匹配、Map.of 超过 10 对、局部变量未初始化），"
            + "严格按 Java 类型规范修正后，重新输出完整代码。\n";

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", SystemPrompts.GENERATE_BACKEND_SYSTEM),
            Map.of("role", "user", "content", prompt)
        );

        String raw = streamLLM(llm, messages, agent, sessionId);
        if (raw == null || raw.isEmpty()) {
            throw new RuntimeException("二次生成 LLM 返回为空");
        }

        String code = extractPluginBlock(raw, str(pluginSpec.get("name")));
        if (code == null || code.isEmpty()) {
            throw new RuntimeException("二次生成无法提取 Java 代码");
        }
        return code;
    }

    /** 流式 LLM 调用，每 chunk 推 code_stream */
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
            System.err.println("[create_tool] LLM 流式失败: " + errorMsg.get());
            return "";
        }

        return buffer.toString();
    }

    // ============================================================
    // WS 推送
    // ============================================================

    private void pushCode(Agent agent, String sessionId, String chunk) {
        try {
            Envelop env = new Envelop("builtins/tools/create_tool", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "code_stream");
            data.put("chunk", chunk);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Exception ignored) {}
    }

    private void pushProgress(Agent agent, String sessionId, String step, String msg) {
        try {
            Envelop env = new Envelop("builtins/tools/create_tool", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "progress");
            data.put("step", step);
            data.put("msg", msg);
            data.put("message", msg);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Exception ignored) {}
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
