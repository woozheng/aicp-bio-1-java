import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * builtins/agents/main_agent — 主控制台 Agent（简化版）
 *
 * receiver 从"文件路径"推：builtins/agents/main_agent
 *
 * 简化点（相对于 TS 版）：
 *   - 不做流式推送
 *   - 不做异步回调（create_tool / fix_tool 同步执行）
 *   - 不做分身（task_manager 后补）
 *   - 不做技能（skill_loader 后补）
 *
 * 内含包私有类：InformationFlow / ObjectMapperHolder / LLMOutputParser
 *                / FastValidator / CoreProcessor / PromptManager
 */
public class main_agent implements Plugin {

    static final Path MEMORY_DIR = Path.of("data", "memories", "main_agent");
    static final int MAX_RECURSION_DEPTH = 40;
    static final int MAX_FLOW_ENTRIES = 500;

    private static final Map<String, InformationFlow> SESSION_FLOWS = new ConcurrentHashMap<>();
    private static final Map<String, Object> SESSION_LOCKS = new ConcurrentHashMap<>();

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        try {
            String action = envelop.payload.get("action") != null
                ? envelop.payload.get("action").toString()
                : "chat";

            String sessionId = str(envelop.payload.get("session_id"));
            if (sessionId.isEmpty() && envelop.meta.get("session_id") != null) {
                sessionId = envelop.meta.get("session_id").toString();
            }
            if (sessionId.isEmpty()) sessionId = "default";

            switch (action) {
                case "get_state": {
                    String type = envelop.payload.get("type") != null
                        ? envelop.payload.get("type").toString() : "exp";
                    String content = type.equals("exp")
                        ? loadExperience(sessionId)
                        : loadTaskboard(sessionId);
                    envelop.payload = Map.of("ok", true, "data",
                        Map.of("type", type, "content", content, "size", content.length()));
                    return envelop;
                }
                case "save_state": {
                    String type = envelop.payload.get("type") != null
                        ? envelop.payload.get("type").toString() : "exp";
                    String content = str(envelop.payload.get("content"));
                    Path dir = type.equals("exp")
                        ? MEMORY_DIR.resolve("experiences")
                        : MEMORY_DIR.resolve("taskboards");
                    Files.createDirectories(dir);
                    Path file = dir.resolve(sessionId + (type.equals("exp") ? "_backpack.txt" : "_taskboard.txt"));
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    envelop.payload = Map.of("ok", true, "data",
                        Map.of("type", type, "saved", true, "size", content.length()));
                    return envelop;
                }
                case "clear_memory": {
                    InformationFlow flow = getOrCreateFlow(sessionId);
                    flow.clear();
                    flow.save();
                    envelop.payload = Map.of("ok", true);
                    return envelop;
                }
                case "get_session": {
                    InformationFlow flow = getOrCreateFlow(sessionId);
                    List<Map<String, Object>> entries = flow.getFlow();

                    Set<String> days = new TreeSet<>(Comparator.reverseOrder());
                    for (Map<String, Object> e : entries) {
                        String ts = str(e.get("timestamp"));
                        if (ts.length() >= 10) days.add(ts.substring(0, 10));
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("flow", entries);
                    out.put("all_days", new ArrayList<>(days));
                    out.put("current_day", java.time.LocalDate.now().toString());
                    envelop.payload = out;
                    return envelop;
                }
                case "get_day_history": {
                    String date = str(envelop.payload.get("date"));
                    if (date.isEmpty()) {
                        envelop.payload = Map.of("ok", false, "error", "缺少 date");
                        return envelop;
                    }

                    InformationFlow flow = getOrCreateFlow(sessionId);
                    List<Map<String, Object>> all = flow.getFlow();
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (Map<String, Object> e : all) {
                        String ts = str(e.get("timestamp"));
                        if (ts.startsWith(date)) filtered.add(e);
                    }

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.put("entries", filtered);
                    envelop.payload = out;
                    return envelop;
                }
                case "chat":
                    break;
                default:
                    envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
                    return envelop;
            }

            String userInput = str(envelop.payload.get("content")).trim();
            if (userInput.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "请输入内容");
                return envelop;
            }

            Object llmObj = agent.get("llm");
            if (!(llmObj instanceof LLM)) {
                envelop.payload = Map.of("ok", false, "error", "LLM 未配置");
                return envelop;
            }

            InformationFlow flow = getOrCreateFlow(sessionId);
            flow.appendUser(userInput);

            Object lock = SESSION_LOCKS.computeIfAbsent(sessionId, k -> new Object());
            CoreProcessor processor = new CoreProcessor();
            Map<String, Object> result;
            synchronized (lock) {
                result = processor.process(agent, sessionId, flow, 0);
            }

            envelop.payload = result;
            return envelop;
        } catch (Exception e) {
            String errorStr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            envelop.payload = Map.of(
                "ok", false,
                "call", "reply",
                "content", "系统错误: " + errorStr,
                "error", errorStr
            );
            return envelop;
        }
    }

    static InformationFlow getOrCreateFlow(String sessionId) {
        return SESSION_FLOWS.computeIfAbsent(sessionId, InformationFlow::new);
    }

    static String loadExperience(String sessionId) {
        Path file = MEMORY_DIR.resolve("experiences").resolve(sessionId + "_backpack.txt");
        if (!Files.exists(file)) return "";
        try { return Files.readString(file, StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    static String loadTaskboard(String sessionId) {
        Path file = MEMORY_DIR.resolve("taskboards").resolve(sessionId + "_taskboard.txt");
        if (!Files.exists(file)) return "";
        try { return Files.readString(file, StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}


// ============================================================
// InformationFlow — 信息流（持久化）
// ============================================================

class InformationFlow {

    private final String sessionId;
    private final Path flowFile;
    private final List<Map<String, Object>> flow = new ArrayList<>();

    public InformationFlow(String sessionId) {
        this.sessionId = sessionId;
        this.flowFile = main_agent.MEMORY_DIR.resolve(sessionId + "_flow.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(flowFile)) return;
        try {
            String raw = Files.readString(flowFile, StandardCharsets.UTF_8);
            List<Map<String, Object>> data = ObjectMapperHolder.MAPPER.readValue(raw, List.class);
            if (data != null) {
                if (data.size() > main_agent.MAX_FLOW_ENTRIES) {
                    flow.addAll(data.subList(data.size() - main_agent.MAX_FLOW_ENTRIES, data.size()));
                } else {
                    flow.addAll(data);
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try {
            Files.createDirectories(main_agent.MEMORY_DIR);
            String content = ObjectMapperHolder.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(flow);
            Path tmp = flowFile.resolveSibling(flowFile.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, flowFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {}
    }

    public synchronized void appendUser(String content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("from", "user");
        entry.put("content", content);
        flow.add(entry);
        save();
    }

    public synchronized void appendAi(String think, Map<String, Object> output) {
        String call = main_agent.str(output.get("call"));
        Set<String> validCalls = Set.of("reply", "ask_user", "use_tool",
            "create_tool", "fix_tool", "remove_tool",
            "query_plugin", "aicp_chat", "add_experience");
        if (!validCalls.contains(call)) return;

        String content = main_agent.str(output.get("content"));
        if (isLlmErrorString(content)) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("from", "ai");
        entry.put("think", think);
        entry.put("call", call);
        entry.put("content", output.get("content"));
        entry.put("args", output.get("args"));
        entry.put("retain", output.getOrDefault("retain", 0));
        flow.add(entry);
        save();
    }

    public synchronized void appendSystem(String action, String result, Map<String, Object> detail, int retain) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("from", "system");
        entry.put("action", action);
        entry.put("result", result);
        entry.put("detail", detail != null ? detail : new LinkedHashMap<>());
        entry.put("retain", retain);
        flow.add(entry);
        save();
    }

    public synchronized List<Map<String, Object>> getFlow() {
        return new ArrayList<>(flow);
    }

    public synchronized void clear() {
        flow.clear();
    }

    public synchronized String getContext() {
        return getContext(20);
    }

    public synchronized String getContext(int limit) {
        if (flow.isEmpty()) return "（暂无对话记录）";

        List<Map<String, Object>> entries = flow.size() > limit
            ? flow.subList(flow.size() - limit, flow.size())
            : flow;

        List<String> lines = new ArrayList<>();
        int total = entries.size();
        String IND = "  ";

        int[] aiDistances = new int[total];
        int aiCount = 0;
        for (int i = total - 1; i >= 0; i--) {
            aiDistances[i] = aiCount;
            if ("ai".equals(entries.get(i).get("from"))) aiCount++;
        }

        for (int idx = 0; idx < total; idx++) {
            Map<String, Object> entry = entries.get(idx);
            String tsFull = main_agent.str(entry.get("timestamp"));
            String ts = tsFull.length() >= 19 ? tsFull.substring(5, 19) : tsFull;
            String from = main_agent.str(entry.get("from"));
            int distance = aiDistances[idx];

            if ("user".equals(from)) {
                String content = main_agent.str(entry.get("content"));
                if (!content.isEmpty()) lines.add("[" + ts + "] 👤 " + content);
            } else if ("ai".equals(from)) {
                String think = main_agent.str(entry.get("think"));
                String content = main_agent.str(entry.get("content"));
                String call = main_agent.str(entry.get("call"));
                Object argsObj = entry.get("args");
                int retain = num(entry.get("retain"), 0);
                boolean inRetain = retain <= 0 || distance < retain;

                if (!think.isEmpty()) lines.add(IND + "[" + ts + "] 💭 " + think);

                if (!content.isEmpty() && ("reply".equals(call) || "ask_user".equals(call))) {
                    lines.add(IND + "[" + ts + "] 🤖 " + content);
                }

                if (!call.isEmpty() && !"reply".equals(call) && !"ask_user".equals(call)) {
                    String argsStr = toJson(argsObj);
                    String display = inRetain ? argsStr : shrink(argsStr, 300);
                    lines.add(IND + IND + "[" + ts + "] 🔧 调用 " + call);
                    lines.add(indentBlock(display, 3));
                }
            } else if ("system".equals(from)) {
                String action = main_agent.str(entry.get("action"));
                String result = main_agent.str(entry.get("result"));
                Object detailObj = entry.get("detail");
                Map<String, Object> detail = detailObj instanceof Map<?, ?> m
                    ? castMap(m) : new LinkedHashMap<>();
                int retain = num(entry.get("retain"), 0);
                boolean inRetain = retain <= 0 || distance < retain;

                switch (action) {
                    case "call_start": {
                        String callId = main_agent.str(detail.get("call_id"));
                        String target = main_agent.str(detail.get("target"));
                        String status = main_agent.str(detail.getOrDefault("status", "pending"));
                        String paramsStr = toJson(detail.get("params"));
                        String display = inRetain ? paramsStr : shrink(paramsStr, 200);
                        String tag = "processing".equals(status) ? "⏳" : "completed".equals(status) ? "✅" : "";
                        lines.add(IND + IND + IND + "[" + ts + "] 📤 " + target + " [id=" + callId + "] " + tag);
                        lines.add(indentBlock(display, 4));
                        break;
                    }
                    case "call_end": {
                        String callId = main_agent.str(detail.get("call_id"));
                        String target = main_agent.str(detail.get("target"));
                        String resultStr = toJson(detail.get("result"));
                        String display = inRetain ? resultStr : shrink(resultStr, 200);
                        lines.add(IND + IND + IND + "[" + ts + "] ✅ " + target + " 调用完成 [id=" + callId + "]");
                        lines.add(IND + IND + IND + IND + "结果：");
                        lines.add(indentBlock(display, 4));
                        break;
                    }
                    case "call_error": {
                        String callId = main_agent.str(detail.get("call_id"));
                        String error = main_agent.str(detail.get("error"));
                        String display = inRetain ? error : shrink(error, 200);
                        lines.add(IND + IND + IND + "[" + ts + "] ❌ [id=" + callId + "]");
                        lines.add(indentBlock(display, 4));
                        break;
                    }
                    case "validation_error": {
                        String fullResult = main_agent.str(detail.get("error"));
                        lines.add(IND + IND + IND + "[" + ts + "] ❌ " + fullResult);
                        break;
                    }
                    default: {
                        if (!result.isEmpty()) {
                            lines.add(IND + IND + IND + "[" + ts + "] 📊 " + result);
                        }
                    }
                }
            }
        }

        return String.join("\n", lines);
    }

    private static boolean isLlmErrorString(String text) {
        if (text == null || text.isEmpty()) return false;
        String[] prefixes = {
            "[LLM stream error:", "[LLM请求失败:", "[系统错误:", "[服务请求超时",
            "[模型返回空响应", "[LLM 达到最大重试次数]", "[LLM 未配置]", "[空响应]"
        };
        for (String p : prefixes) if (text.startsWith(p)) return true;
        return false;
    }

    private static String shrink(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "... (已收缩，共 " + text.length() + " 字)";
    }

    private static String indentBlock(String text, int level) {
        String pad = "  ".repeat(level);
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            if (!lines[i].isEmpty()) sb.append(pad);
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String toJson(Object o) {
        try {
            return ObjectMapperHolder.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }

    private static int num(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        return def;
    }
}


// ============================================================
// ObjectMapperHolder — 共享 Jackson 实例
// ============================================================

class ObjectMapperHolder {
    public static final ObjectMapper MAPPER = new ObjectMapper();
}


// ============================================================
// LLMOutputParser — LLM 输出解析
// ============================================================

class LLMOutputParser {

    private static final Set<String> VALID_CALLS = Set.of("use_tool", "reply");
    private static final ObjectMapper MAPPER = ObjectMapperHolder.MAPPER;

    public Map<String, Object> parse(String raw) {
        if (raw == null) return errorResponse("输入类型错误", "抱歉，处理过程中出现错误，请稍后重试");

        try {
            String s = raw.trim();
            if (s.isEmpty()) return errorResponse("空输入", "抱歉，我遇到了一些问题...");

            ContentBlock cb = extractContentBlock(s);
            String cleaned = cb.cleaned;
            boolean hasJsonIntent = hasJsonIntent(cleaned);
            JsonResult jr = findAndParseJson(cleaned);

            if (jr.found && jr.result != null) {
                if (!hasJsonIntent) {
                    return makeReply("LLM 直接回复", s);
                }

                String callVal = normalizeCallField(jr.result);
                if (callVal == null) {
                    return makeRetry("JSON 缺少 call 字段|" + toJsonShort(jr.result));
                }
                jr.result.put("call", callVal);

                if (cb.contentBlock != null) {
                    injectContentBlock(jr.result, cb.contentBlock);
                } else {
                    String missing = checkMissingContentBlock(jr.result);
                    if (!missing.isEmpty()) {
                        return makeRetry(missing + " 需要大文本参数，但没有输出 @@CONTENT@@ 块。");
                    }
                }
                return ensureDefaults(jr.result);
            }

            if (hasJsonIntent) {
                return makeRetry("JSON 解析失败|" + s.substring(0, Math.min(300, s.length())));
            }

            return makeReply("LLM 直接回复", s);
        } catch (Exception e) {
            return errorResponse("解析异常", "抱歉，处理过程中出现错误，请稍后重试");
        }
    }

    private static class ContentBlock {
        String contentBlock;
        String cleaned;
    }

    private ContentBlock extractContentBlock(String raw) {
        ContentBlock cb = new ContentBlock();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "@@CONTENT@@\\s*\\n?([\\s\\S]*?)(?:@@END_CONTENT@@|$)"
        );
        java.util.regex.Matcher m = p.matcher(raw);
        if (m.find()) {
            String content = m.group(1).trim();
            if (!content.isEmpty()) {
                cb.contentBlock = content;
                cb.cleaned = raw.substring(0, m.start()) + raw.substring(m.end());
                return cb;
            }
        }
        cb.contentBlock = null;
        cb.cleaned = raw;
        return cb;
    }

    private boolean hasJsonIntent(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String s = raw.trim();
        if (s.startsWith("{") && s.endsWith("}")) return true;
        int idx = s.indexOf('{');
        if (idx == -1) return false;
        String tail = s.substring(idx, Math.min(idx + 200, s.length()));
        String[] keys = {"\"call\"", "'call'", "\"args\"", "'args'", "\"target\"", "'target'", "\"think\"", "'think'"};
        for (String k : keys) if (tail.contains(k)) return true;
        return false;
    }

    private static class JsonResult {
        Map<String, Object> result;
        boolean found;
    }

    @SuppressWarnings("unchecked")
    private JsonResult findAndParseJson(String raw) {
        JsonResult jr = new JsonResult();
        jr.found = false;
        jr.result = null;
        if (raw == null || raw.isEmpty()) return jr;

        String s = unwrapCodeBlock(raw).trim();
        int start = s.indexOf('{');
        if (start == -1) return jr;

        int depth = 0;
        boolean inString = false, escape = false;
        int end = -1;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (ch == '\\') { escape = true; continue; }
            if (ch == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        if (end == -1) return jr;

        String candidate = s.substring(start, end + 1);
        try {
            Map<String, Object> parsed = MAPPER.readValue(candidate, Map.class);
            if (parsed != null) {
                jr.result = parsed;
                jr.found = true;
            }
        } catch (Exception ignored) {}
        return jr;
    }

    private String unwrapCodeBlock(String raw) {
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
            "```json\\s*\\n?([\\s\\S]*?)\\n?```"
        );
        java.util.regex.Matcher m1 = p1.matcher(raw);
        if (m1.find()) {
            String inner = m1.group(1).trim();
            String before = raw.substring(0, m1.start()).trim();
            String after = raw.substring(m1.end()).trim();
            return joinNonEmpty(before, inner, after);
        }
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
            "```\\s*\\n?([\\s\\S]*?)\\n?```"
        );
        java.util.regex.Matcher m2 = p2.matcher(raw);
        if (m2.find()) {
            String inner = m2.group(1).trim();
            String before = raw.substring(0, m2.start()).trim();
            String after = raw.substring(m2.end()).trim();
            return joinNonEmpty(before, inner, after);
        }
        return raw;
    }

    private static String joinNonEmpty(String... parts) {
        List<String> nonEmpty = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) nonEmpty.add(p);
        return String.join("\n", nonEmpty);
    }

    private String normalizeCallField(Map<String, Object> result) {
        Object callVal = null;
        for (String key : new String[]{"call", "Call", "CALL"}) {
            if (result.containsKey(key)) {
                callVal = result.remove(key);
                break;
            }
        }

        if (callVal != null) {
            String lower = callVal.toString().toLowerCase();
            if ("_retry".equals(lower)) return "_retry";   // ★ 熔断标记
            if (VALID_CALLS.contains(lower)) return lower;
        }

        Object argsObj = result.get("args");
        if (argsObj instanceof Map<?, ?> args && args.get("target") != null) {
            return "use_tool";
        }

        if (result.containsKey("target")) {
            Object args2 = result.get("args");
            Map<String, Object> args;
            if (!(args2 instanceof Map)) {
                args = new LinkedHashMap<>();
                result.put("args", args);
            } else {
                args = castMap((Map<?, ?>) args2);
            }
            args.put("target", result.remove("target"));
            if (result.containsKey("action")) args.put("action", result.remove("action"));
            if (result.containsKey("params")) args.put("params", result.remove("params"));
            return "use_tool";
        }

        if (result.containsKey("content") && result.size() <= 3) {
            return "reply";
        }

        return null;
    }

    private void injectContentBlock(Map<String, Object> result, String contentBlock) {
        String call = main_agent.str(result.get("call"));

        if ("reply".equals(call) || "ask_user".equals(call)) {
            result.put("content", contentBlock);
            return;
        }
        if (!"use_tool".equals(call)) return;

        Object argsObj = result.get("args");
        Map<String, Object> args;
        if (!(argsObj instanceof Map)) {
            args = new LinkedHashMap<>();
            result.put("args", args);
        } else {
            args = castMap((Map<?, ?>) argsObj);
            result.put("args", args);
        }

        Object paramsObj = args.get("params");
        Map<String, Object> params;
        if (!(paramsObj instanceof Map)) {
            params = new LinkedHashMap<>();
            args.put("params", params);
        } else {
            params = castMap((Map<?, ?>) paramsObj);
            args.put("params", params);
        }

        String target = main_agent.str(args.get("target"));
        String action = main_agent.str(args.get("action"));
        boolean isClear = "clear".equals(action);

        switch (target) {
            case "builtins/tools/add_experience":
                if (!isClear) params.put("experience", contentBlock);
                break;
            case "builtins/tools/add_task_board":
                if (!isClear) params.put("content", contentBlock);
                break;
            case "builtins/tools/aicp_chat":
                params.put("task", contentBlock);
                break;
            case "builtins/tools/fix_tool":
                params.put("issue", contentBlock);
                break;
            case "os/file_utils_api":
                params.put("content", contentBlock);
                break;
            default:
                params.put("content", contentBlock);
        }
    }

    private String checkMissingContentBlock(Map<String, Object> result) {
        if (!"use_tool".equals(main_agent.str(result.get("call")))) return "";
        Object argsObj = result.get("args");
        if (!(argsObj instanceof Map)) return "";
        Map<String, Object> args = castMap((Map<?, ?>) argsObj);
        String target = main_agent.str(args.get("target"));
        String action = main_agent.str(args.get("action"));
        Object paramsObj = args.get("params");
        Map<String, Object> params = paramsObj instanceof Map
            ? castMap((Map<?, ?>) paramsObj) : new LinkedHashMap<>();
        boolean isClear = "clear".equals(action);

        switch (target) {
            case "builtins/tools/add_experience":
                if (isClear) return "";
                if (!params.containsKey("experience")) return "add_experience（需要 experience）";
                break;
            case "builtins/tools/add_task_board":
                if (isClear) return "";
                if (!params.containsKey("content")) return "add_task_board（需要 content）";
                break;
            case "builtins/tools/aicp_chat":
                if (!params.containsKey("task")) return "aicp_chat（需要 task）";
                break;
            case "builtins/tools/fix_tool":
                if (!params.containsKey("issue")) return "fix_tool（需要 issue）";
                break;
            case "os/file_utils_api":
                if ("write_file".equals(action) || "append_file".equals(action) || "apply_patch".equals(action)) {
                    if (!params.containsKey("content")) return action + "（需要 content）";
                }
                break;
        }
        return "";
    }

    private Map<String, Object> makeRetry(String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("think", "格式错误，需要重试");
        r.put("call", "_retry");
        r.put("content", "");
        r.put("args", new LinkedHashMap<>());
        r.put("_validation_reason", reason);
        return r;
    }

    private Map<String, Object> makeReply(String think, String content) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("think", think);
        r.put("call", "reply");
        r.put("content", content);
        r.put("args", new LinkedHashMap<>());
        return r;
    }

    private Map<String, Object> errorResponse(String think, String content) {
        Map<String, Object> r = makeReply(think, content);
        r.put("_validation_failed", true);
        r.put("_validation_reason", think);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureDefaults(Map<String, Object> result) {
        if (!result.containsKey("think")) result.put("think", "");
        if (!result.containsKey("call")) result.put("call", "reply");
        if (!(result.get("args") instanceof Map)) result.put("args", new LinkedHashMap<>());
        String call = main_agent.str(result.get("call"));
        if ("reply".equals(call) || "ask_user".equals(call)) {
            Object c = result.get("content");
            if (c == null) result.put("content", "");
            else if (!(c instanceof String)) result.put("content", c.toString());
        }
        result.put("think", main_agent.str(result.get("think")));
        result.put("call", main_agent.str(result.get("call")));
        return result;
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }

    private static String toJsonShort(Object o) {
        try {
            String s = MAPPER.writeValueAsString(o);
            return s.length() > 300 ? s.substring(0, 300) : s;
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}


// ============================================================
// FastValidator — LLM 输出格式校验
// ============================================================

class FastValidator {

    private static final Set<String> VALID_CALLS = Set.of("use_tool", "reply");

    @SuppressWarnings("unchecked")
    public static Map<String, Object> validate(Map<String, Object> output) {
        if ("_retry".equals(main_agent.str(output.get("call")))) return output;

        if (output.containsKey("use_tool")) {
            Object targetObj = output.remove("use_tool");
            Object paramsObj = output.remove("参数");
            if (targetObj == null || main_agent.str(targetObj).isEmpty()) {
                output.put("_validation_failed", true);
                output.put("_validation_reason", "缺少 use_tool 目标");
                return output;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("target", main_agent.str(targetObj));
            args.put("params", paramsObj instanceof Map ? paramsObj : new LinkedHashMap<>());
            output.put("call", "use_tool");
            output.put("args", args);
        }

        String call = main_agent.str(output.get("call"));
        if (call.isEmpty()) {
            output.put("_validation_failed", true);
            output.put("_validation_reason", "缺少 call 字段，必须为 use_tool 或 reply");
            return output;
        }
        if (!VALID_CALLS.contains(call)) {
            output.put("_validation_failed", true);
            output.put("_validation_reason", "未知 call '" + call + "'，只允许 use_tool 或 reply");
            return output;
        }

        if ("reply".equals(call)) {
            String content = main_agent.str(output.get("content"));
            if (content.isEmpty()) {
                output.put("_validation_failed", true);
                output.put("_validation_reason", "JSON reply 缺少 content 字段。回复用户请用纯文本，不要用 JSON。");
                return output;
            }
            return output;
        }

        if ("use_tool".equals(call)) {
            Object argsObj = output.get("args");
            if (!(argsObj instanceof Map)) {
                output.put("_validation_failed", true);
                output.put("_validation_reason", "缺少 args");
                return output;
            }
            Map<String, Object> args = castMap((Map<?, ?>) argsObj);

            String target = main_agent.str(args.get("target"));
            if (target.isEmpty()) {
                output.put("_validation_failed", true);
                output.put("_validation_reason", "缺少 target");
                return output;
            }

            if ("builtins/agents/main_agent".equals(target)) {
                String action = main_agent.str(args.get("action"));
                Object paramsObj = args.get("params");
                Map<String, Object> params = paramsObj instanceof Map
                    ? castMap((Map<?, ?>) paramsObj) : new LinkedHashMap<>();
                String content = main_agent.str(params.get("content"));
                if ("reply".equals(action) && !content.isEmpty()) {
                    output.put("call", "reply");
                    output.put("content", content);
                    output.put("args", new LinkedHashMap<>());
                    output.remove("_validation_failed");
                    output.remove("_validation_reason");
                    return output;
                }
                output.put("_validation_failed", true);
                output.put("_validation_reason",
                    "禁止 use_tool 直接调用 builtins/agents/main_agent。\n" +
                    "要回复用户，直接输出纯文本（call=reply）。");
                return output;
            }

            if ("reply".equals(target) || "builtins/agents/reply".equals(target)) {
                args.put("target", "builtins/agents/main_agent/reply");
                output.put("args", args);
            }

            if (!args.containsKey("params")) {
                args.put("params", new LinkedHashMap<>());
                output.put("args", args);
            }
        }

        output.remove("_validation_failed");
        output.remove("_validation_reason");
        return output;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }
}


// ============================================================
// CoreProcessor — 核心处理循环
// ============================================================

class CoreProcessor {

    private final LLMOutputParser parser = new LLMOutputParser();

    // 熔断阈值
    private static final int THINK_MAX_CHARS = 1600;
    private static final long MAX_STREAM_TIME_MS = 240_000L;

    public Map<String, Object> process(Agent agent, String sessionId,
                                       InformationFlow flow, int depth) {
        return processInternal(agent, sessionId, flow, depth);
    }

    private Map<String, Object> processInternal(Agent agent, String sessionId,
                                                InformationFlow flow, int depth) {
        if (depth > main_agent.MAX_RECURSION_DEPTH) {
            return reply("处理步骤过多，请简化需求后重试", false);
        }

        // ★ 每轮开始：推"思考中"
        pushEvent(agent, sessionId, progressEvent("thinking", "第 " + (depth + 1) + " 轮推理"));

        String raw;
        try {
            raw = think(agent, flow, sessionId, depth);
        } catch (Exception e) {
            return reply("LLM 调用失败: " + e.getMessage(), false);
        }

        Map<String, Object> output = parser.parse(raw);

        if ("_retry".equals(main_agent.str(output.get("call")))) {
            String reason = main_agent.str(output.get("_validation_reason"));
            String errorMsg = buildRetryMessage(reason);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("error", errorMsg);
            flow.appendSystem("validation_error", "输出格式错误，请重新输出", detail, 5);
            return processInternal(agent, sessionId, flow, depth + 1);
        }

        output = FastValidator.validate(output);

        if (Boolean.TRUE.equals(output.get("_validation_failed"))) {
            String reason = main_agent.str(output.get("_validation_reason"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("error", reason);
            flow.appendSystem("validation_error", "输出格式错误，请重新输出", detail, 5);
            return processInternal(agent, sessionId, flow, depth + 1);
        }

        String think = main_agent.str(output.get("think"));
        String call = main_agent.str(output.get("call"));
        String content = main_agent.str(output.get("content"));
        Object argsObj = output.get("args");
        Map<String, Object> args = argsObj instanceof Map ? castMap((Map<?, ?>) argsObj)
            : new LinkedHashMap<>();
        int retain = output.get("retain") instanceof Number n ? n.intValue() : 3;

        flow.appendAi(think, output);

        if ("reply".equals(call)) {
            if (content.isEmpty()) content = "好的，已处理";
            return reply(content, false);
        }

        if ("use_tool".equals(call)) {
            String target = main_agent.str(args.get("target"));

            if ("builtins/agents/main_agent/reply".equals(target)) {
                Object paramsObj = args.get("params");
                Map<String, Object> params = paramsObj instanceof Map
                    ? castMap((Map<?, ?>) paramsObj) : new LinkedHashMap<>();
                String c = main_agent.str(params.get("content"));
                if (c.isEmpty()) c = "好的，已处理";
                return reply(c, false);
            }

            executeTool(agent, sessionId, flow, target, args, retain);
            return processInternal(agent, sessionId, flow, depth + 1);
        }

        return reply("未知 call: " + call, false);
    }

    // ============================================================
    // think — 流式 + 三层熔断
    // ============================================================

    private String think(Agent agent, InformationFlow flow,
                         String sessionId, int depth) throws Exception {
        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            throw new RuntimeException("LLM 不可用");
        }

        String context = flow.getContext();
        String activeSkill = fetchActiveSkill(agent, sessionId);
        String systemPrompt = PromptManager.buildSystem(sessionId);
        String userPrompt = PromptManager.buildUser(sessionId, context, depth, activeSkill);

        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        );

        final StringBuilder buffer = new StringBuilder();
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        final AtomicReference<String> interruptReason = new AtomicReference<>("");
        final long startTime = System.currentTimeMillis();
        final int[] tokenCount = {0};
        final String sid = sessionId;

        llm.chatStream(messages, null, null, (delta, done, error) -> {
            if (interrupted.get()) return;

            if (delta != null && !delta.isEmpty()) {
                buffer.append(delta);
                tokenCount[0]++;
                String raw = buffer.toString();

                // -------- 熔断 1：单次调用超时 --------
                if (System.currentTimeMillis() - startTime > MAX_STREAM_TIME_MS) {
                    interrupted.set(true);
                    interruptReason.set("单次调用超时");
                    pushEvent(agent, sid, wsErrorEvent("⚠️ 生成超时（" + (MAX_STREAM_TIME_MS / 1000) + "s），已中断，正在重试..."));
                    return;
                }

                // -------- 熔断 2：think 字段过长 --------
                if (raw.contains("\"think\"") && !raw.contains("\"call\"")) {
                    int thinkStart = raw.indexOf("\"think\"");
                    if (thinkStart >= 0 && raw.length() - thinkStart > THINK_MAX_CHARS) {
                        interrupted.set(true);
                        interruptReason.set("think 字段过长");
                        pushEvent(agent, sid, wsErrorEvent("⚠️ think 字段过长，已中断，正在重试..."));
                        return;
                    }
                }

                // -------- 熔断 3：检测多个 JSON --------
                String beforeContent = raw.contains("@@CONTENT@@")
                    ? raw.substring(0, raw.indexOf("@@CONTENT@@")) : raw;

                // 早检测：} 后紧跟 {
                if (beforeContent.matches("(?s).*}\\s*\\{.*")) {
                    interrupted.set(true);
                    interruptReason.set("检测到多个 JSON 对象");
                    pushEvent(agent, sid, wsErrorEvent("⚠️ 检测到异常输出（多个 JSON），已中断，正在重试..."));
                    return;
                }

                // 中检测：每 5 token 扫一次完整顶层对象
                if (tokenCount[0] % 5 == 0 && countChar(beforeContent, '{') >= 2) {
                    int jsonCount = countJsonObjects(beforeContent);
                    if (jsonCount >= 2) {
                        interrupted.set(true);
                        interruptReason.set("检测到多个 JSON 对象");
                        pushEvent(agent, sid, wsErrorEvent("⚠️ 检测到异常输出（" + jsonCount + " 个 JSON），已中断，正在重试..."));
                        return;
                    }
                }

                // 正常推流
                pushEvent(agent, sid, streamEvent("summary_stream", delta));
            }

            if (done && error != null && !error.isEmpty()) {
                buffer.append(error);
            }
        });

        // ★ 熔断 → 返回 _retry JSON
        if (interrupted.get()) {
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("think", "输出违规，需要重试");
            retry.put("call", "_retry");
            retry.put("content", "");
            retry.put("args", new LinkedHashMap<>());
            retry.put("_validation_reason", interruptReason.get());
            try {
                return ObjectMapperHolder.MAPPER.writeValueAsString(retry);
            } catch (Exception e) {
                return "{\"think\":\"熔断\",\"call\":\"_retry\",\"content\":\"\",\"args\":{},\"_validation_reason\":\"熔断\"}";
            }
        }

        return buffer.toString();
    }

    private String fetchActiveSkill(Agent agent, String sessionId) {
        try {
            Envelop env = new Envelop("builtins/agents/main_agent", "builtins/tools/skill_loader");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "get_active");
            env.payload = payload;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("session_id", sessionId);
            env.meta = meta;

            Envelop result = agent.call(env);
            if (result == null || result.payload == null) return "";
            Object content = result.payload.get("content");
            return content != null ? content.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ============================================================
    // executeTool
    // ============================================================

    private void executeTool(Agent agent, String sessionId, InformationFlow flow,
                             String target, Map<String, Object> args, int retain) {
        String toolAction = main_agent.str(args.get("action"));
        Object paramsObj = args.get("params");
        Map<String, Object> toolParams = paramsObj instanceof Map
            ? castMap((Map<?, ?>) paramsObj) : new LinkedHashMap<>();

        String callId = randomHex(8);

        Map<String, Object> startDetail = new LinkedHashMap<>();
        startDetail.put("call_id", callId);
        startDetail.put("target", target);
        startDetail.put("action", toolAction);
        startDetail.put("params", toolParams);
        startDetail.put("status", "pending");
        flow.appendSystem("call_start", "📤 调用工具: " + target, startDetail, retain);

        // ★ 推"执行中"
        pushEvent(agent, sessionId, progressEvent("executing", "⚡ 正在 " + target + "..."));

        Envelop env = new Envelop("builtins/agents/main_agent", target);
        Map<String, Object> payload = new LinkedHashMap<>(toolParams);
        if (!toolAction.isEmpty()) payload.put("action", toolAction);
        env.payload = payload;

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("session_id", sessionId);
        meta.put("_call_id", callId);
        env.meta = meta;

        try {
            Envelop result = agent.call(env);
            Map<String, Object> endDetail = new LinkedHashMap<>();
            endDetail.put("call_id", callId);
            endDetail.put("target", target);
            endDetail.put("result", result != null && result.payload != null
                ? result.payload : new LinkedHashMap<>());
            flow.appendSystem("call_end", "📨 调用完成: " + target, endDetail, retain);

            // ★ 推"完成"
            pushEvent(agent, sessionId, progressEvent("done", "✅ " + target + " 完成"));
        } catch (Exception e) {
            Map<String, Object> errDetail = new LinkedHashMap<>();
            errDetail.put("call_id", callId);
            errDetail.put("target", target);
            errDetail.put("error", e.getMessage());
            flow.appendSystem("call_error", "❌ 调用失败: " + target, errDetail, retain);

            // ★ 推"错误"
            pushEvent(agent, sessionId, wsErrorEvent("❌ " + target + " 失败: " + e.getMessage()));
        }
    }

    // ============================================================
    // WS 推送
    // ============================================================

    private void pushEvent(Agent agent, String sessionId, Map<String, Object> data) {
        try {
            Envelop env = new Envelop("builtins/agents/main_agent", "os/_websocket");
            env.payload.put("action", "push");
            env.payload.put("channel_id", "pa_" + sessionId);
            env.payload.put("data", data);
            agent.call(env);
        } catch (Exception ignored) {}
    }

    private Map<String, Object> progressEvent(String step, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "progress");
        m.put("step", step);
        m.put("agent_type", "main");
        m.put("msg", msg);
        m.put("message", msg);   // ★ 双写，兼容前端
        return m;
    }

    private Map<String, Object> streamEvent(String type, String chunk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("chunk", chunk);
        return m;
    }

    private Map<String, Object> wsErrorEvent(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("content", msg);
        return m;
    }

    // ============================================================
    // 熔断检测辅助
    // ============================================================

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** 检测 raw 里有几个含 call/think/args 的完整 JSON 对象 */
    private static int countJsonObjects(String raw) {
        int count = 0;
        int i = 0;
        while (i < raw.length()) {
            int start = raw.indexOf('{', i);
            if (start < 0) break;
            int depth = 0;
            boolean inString = false, escape = false;
            int end = -1;
            for (int j = start; j < raw.length(); j++) {
                char ch = raw.charAt(j);
                if (escape) { escape = false; continue; }
                if (ch == '\\') { escape = true; continue; }
                if (ch == '"') { inString = !inString; continue; }
                if (inString) continue;
                if (ch == '{') depth++;
                else if (ch == '}') { depth--; if (depth == 0) { end = j; break; } }
            }
            if (end < 0) break;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> obj = ObjectMapperHolder.MAPPER.readValue(
                    raw.substring(start, end + 1), Map.class);
                if (obj.containsKey("call") || obj.containsKey("think") || obj.containsKey("args")) {
                    count++;
                }
            } catch (Exception ignored) {}
            i = end + 1;
        }
        return count;
    }

    // ============================================================
    // reply / buildRetryMessage / castMap / randomHex
    // ============================================================

    private Map<String, Object> reply(String content, boolean waiting) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("waiting", waiting);
        r.put("call", "reply");
        r.put("content", content);
        r.put("args", new LinkedHashMap<>());
        return r;
    }

    private String buildRetryMessage(String reason) {
        if (reason.contains("多个 JSON")) {
            return "❌ 你上一轮输出了多个 JSON 对象，系统只执行了第一个，已中断生成。\n\n" +
                "【规则】每轮只能输出 1 个 JSON。\n" +
                "【怎么办】如果需要多个操作，请分多轮：本轮输出第 1 个，执行完下一轮再输出第 2 个。\n" +
                "【示例】本轮只输出：\n" +
                "  {\"think\":\"先读README\",\"call\":\"use_tool\",\"retain\":3,\"args\":{\"target\":\"os/file_utils_api\",\"action\":\"read_file\",\"params\":{\"path\":\"README.md\"}}}";
        }
        if (reason.contains("think 过长")) {
            return "❌ 你上一轮的 think 字段过长（超过 1600 字符），系统已中断生成。\n\n" +
                "【think 是什么】一句话说明\"下一步做什么、为什么\"，不是内心戏。\n" +
                "【怎么改】控制在 100 字以内。\n" +
                "【正确示例】\"用户要分析项目结构，我先列目录\"\n" +
                "请重新输出，think 控制在 100 字以内。";
        }
        if (reason.contains("单次调用超时")) {
            return "❌ 单次 LLM 调用超过 240 秒，系统已中断。\n" +
                "请简化输出，或直接回复用户当前进展。";
        }
        if (reason.contains("JSON 缺少 call")) {
            return "❌ 输出的 JSON 缺少 call 字段。\n" +
                "【正确格式】{\"think\":\"...\",\"call\":\"use_tool\",\"retain\":3,\"args\":{...}}";
        }
        if (reason.contains("JSON 解析失败")) {
            return "❌ JSON 解析失败。\n【常见原因】括号不闭合、字符串未转义、多个 JSON。";
        }
        if (reason.contains("缺少") || reason.contains("需要")) {
            return "❌ " + reason;
        }
        return "❌ 输出不符合要求：" + reason;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }

    private static String randomHex(int n) {
        byte[] bytes = new byte[(n + 1) / 2];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.substring(0, n);
    }
}


// ============================================================
// PromptManager — prompt 构建
// ============================================================

class PromptManager {

    private static final int MAX_RECURSION_DEPTH = 40;

    private static final String SYSTEM_PROMPT = """
        你是 BIO-java-1，自举式 AI 助手。Session: __SESSION_ID__

        你可以调度、创建、修复、删除插件来实现自我迭代；基于经验背包复用过往能力。
        你不是工具，你是系统的主人。

        ============================================================
        工作方式
        ============================================================
        自递归 Agent：每轮输出一个动作，系统执行后结果注入信息流，你再次决策，直到任务完成。
        复杂任务拆成多步，每轮执行一步。

        ============================================================
        输出规则（最高优先级，唯一标准）
        ============================================================
        每轮只输出以下两种之一，不得有任何其他内容：

        A. 回复用户：直接写文字，不带任何 JSON 标记。
        例：好的，我来帮你创建番茄钟，需要前端界面吗？

        ⚠️ 回复用户时禁止用 JSON 格式。
        ❌ 不要输出 {"call":"reply","content":"..."}
        ✅ 直接输出：好的，我来帮你创建番茄钟

        B. 调用工具：严格输出一个 JSON，不要用 ```json ... ``` 包裹，直接输出纯 JSON：

        {
          "think": "本轮推理，尽量简短",
          "call": "use_tool",
          "retain": N,
          "args": {"target":"插件路径","action":"动作","params":{参数}}
        }

        规则：
        ⚠️ 禁止在 JSON 之前写任何文字。
        ⚠️ 如果你想说明什么，写在 think 字段里。
        1. think 字段用于推理，系统会忽略它，不影响执行。
        think 字段约束：
        - 禁止英文双引号 "，需要引用用「」或 '
        - 禁止反斜杠 \\
        - 禁止换行，用空格代替
        - 禁止 emoji
        2. ⚠️ 一次只输出 1 个 JSON
        3. JSON 必须能被解析，括号闭合。
        4. 有 action 的插件：action 放 args 顶层，参数放 args.params。
        5. 无 action 的插件：直接写 args.params，不写 action。
        6. 需要 content 参数的插件：JSON 中省略 content，在 JSON 之后另起 @@CONTENT@@ 块写内容，@@END_CONTENT@@ 结束。块内纯文本，无需转义。
        7. 大文本参数规则：凡是参数值是"大段文本"（经验、看板、issue 描述、文件内容等超过一句话的），一律省略 JSON 中的该字段，改走 @@CONTENT@@ 块。
           已知适用：add_experience.experience、add_task_board.content、fix_tool.issue、file_utils_api.content。
        8. 禁止：多个 JSON、<think> 标签、<function> 等原生工具调用语法、Python 代码块、额外解释文字。
        9. retain 取值 1-10：
           - 纯一次性、无后续依赖的操作：1
           - 写文件、调用工具后需要确认结果的：3
           - 读文件、查契约等需要跨轮复用的：5
           - 任务收尾决策：保持 3-5

        ============================================================
        高频工具（禁止查契约，直接照抄模板改值）
        ============================================================

        create_tool（创建工具/应用，无 action）：
        {
          "think": "用户要创建X，我先确认需求或直接创建",
          "call": "use_tool",
          "retain": 1,
          "args": {"target":"builtins/tools/create_tool","params":{"name":"项目名","description":"需求描述"}}
        }

        fix_tool（修改已有项目，issue 走 @@CONTENT@@ 块）：

        适用场景（全部走 fix_tool，不要用 file_utils_api 直接改源码）：
        - 修 bug（"X 报错 / 不工作 / 有问题"）
        - 加后端功能（"给 X 加个 Y action / 加个字段"）
        - ★ 加前端页面（"给 X 加页面 / 界面 / 前端"）
        - ★ 改前端（"X 的页面加搜索框 / 改样式 / 改布局 / 改颜色"）
        - ★ 前后端一起改（"给 X 加功能，页面也要能操作"）

        ⚠️ 铁律：
        - 禁止用 file_utils_api 直接改 .java / .html 源码
        - 修改项目必须走 fix_tool，它会自动判断是改后端还是改前端
        - fix_tool 必须带 @@CONTENT@@ 块，否则会报"缺少 issue 参数"

        {
          "think": "用户要改X，我用fix_tool",
          "call": "use_tool",
          "retain": 1,
          "args": {"target":"builtins/tools/fix_tool","params":{"target":"项目名"}}
        }
        @@CONTENT@@
        具体需求写这里，支持多行，无需转义
        @@END_CONTENT@@

        remove_tool（删除项目，无 action）：
        {
          "think": "用户要删除X",
          "call": "use_tool",
          "retain": 1,
          "args": {"target":"builtins/tools/remove_tool","params":{"target":"项目名"}}
        }

        contract_agent（查契约，action 在顶层）：
        {
          "think": "我不确定X插件的接口，查契约",
          "call": "use_tool",
          "retain": 5,
          "args": {"target":"builtins/agents/contract_agent","action":"get","params":{"plugin":"插件全路径"}}
        }

        cogitor（系统地图，action 在顶层）：
        {
          "think": "我需要看系统有哪些插件",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/agents/cogitor","action":"list_plugins","params":{}}
        }

        capabilities（Agent 能力发现，action 在顶层）：
        {
          "think": "看 Agent 上挂了什么能力",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/agents/capabilities","action":"list","params":{}}
        }

        file_utils_api（文件读写，action 在顶层）：

        读文件（≤100KB 返回全文；>100KB 只返回尾部预览）：
        {
          "think": "读取X文件内容",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"os/file_utils_api","action":"read_file","params":{"path":"data/test.txt"}}
        }

        写文件（content 走 @@CONTENT@@ 块）：
        {
          "think": "写入X内容到文件",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"os/file_utils_api","action":"write_file","params":{"path":"data/test.txt"}}
        }
        @@CONTENT@@
        文件内容写这里
        @@END_CONTENT@@

        追加文件（content 走 @@CONTENT@@ 块）：
        {
          "think": "追加内容到文件",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"os/file_utils_api","action":"append_file","params":{"path":"data/test.txt"}}
        }
        @@CONTENT@@
        追加内容写这里
        @@END_CONTENT@@

        局部替换（find 必须唯一匹配，单行短字符串替换）：
        {
          "think": "替换X文件里的Y片段",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"os/file_utils_api","action":"edit_file","params":{"path":"data/test.txt","find":"旧内容","replace":"新内容"}}
        }

        列目录：
        {
          "think": "列出X目录",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"os/file_utils_api","action":"list_dir","params":{"path":"data/"}}
        }

        删文件：
        {
          "think": "删除X文件",
          "call": "use_tool",
          "retain": 1,
          "args": {"target":"os/file_utils_api","action":"delete_file","params":{"path":"data/test.txt"}}
        }

        检查存在：
        {
          "think": "检查X是否存在",
          "call": "use_tool",
          "retain": 2,
          "args": {"target":"os/file_utils_api","action":"exists","params":{"path":"data/test.txt"}}
        }

        建目录：
        {
          "think": "创建X目录",
          "call": "use_tool",
          "retain": 1,
          "args": {"target":"os/file_utils_api","action":"mkdir","params":{"path":"data/workspace/newdir"}}
        }

        add_experience（沉淀经验，experience 走 @@CONTENT@@ 块）：
        {
          "think": "任务完成，沉淀本次经验",
          "call": "use_tool",
          "retain": 2,
          "args": {"target":"builtins/tools/add_experience","params":{"action":"replace"}}
        }
        @@CONTENT@@
        经验内容写这里，可以是任意长文本，无需转义
        @@END_CONTENT@@

        add_task_board（任务看板，content 走 @@CONTENT@@ 块）：
        {
          "think": "更新任务看板",
          "call": "use_tool",
          "retain": 2,
          "args": {"target":"builtins/tools/add_task_board","params":{"action":"replace"}}
        }
        @@CONTENT@@
        看板内容写这里，支持多行，无需转义
        @@END_CONTENT@@

        task_manager（分身任务，action 在顶层）：

        单个分身：
        {
          "think": "这个任务可以并行，我派个分身",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/tools/task_manager","action":"create","params":{"description":"简述","task":"完整任务描述"}}
        }

        查看所有分身：
        {
          "think": "看看分身跑得怎么样",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/tools/task_manager","action":"list","params":{}}
        }

        收集结果：
        {
          "think": "收集分身结果",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/tools/task_manager","action":"collect","params":{}}
        }

        ⚠️ 分身创建后不要重复创建，等回调。分身在独立 session 里跑，不能再调 task_manager。

        skill_loader（技能加载，action 在顶层）：

        搜索技能：
        {
          "think": "这个任务可能有现成技能，先搜一下",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/tools/skill_loader","action":"search","params":{"query":"用户问题原文","keywords":["关键词1","关键词2"]}}
        }

        加载技能：
        {
          "think": "找到相关技能，加载到当前上下文",
          "call": "use_tool",
          "retain": 3,
          "args": {"target":"builtins/tools/skill_loader","action":"load","params":{"skill_id":"xxx_yyy"}}
        }

        ⚠️ load 会覆盖当前 active_skill，一次只加载一个。加载后技能内容会进 prompt 的【当前技能】段（如果你接的话）。

        ============================================================
        工具调用决策
        ============================================================
        遇到需求，按顺序判断：

        0. 任务涉及专业领域（学术/设计/工程/金融/游戏/创作等）
              → 先 skill_loader.search 搜技能
              - query: 用户问题原文
              - keywords: 2-5 个关键词数组（必填！不能空）
              - 有匹配 → skill_loader.load 加载
              - 无匹配 → 继续往下判断
        0.5. 修改已有项目 → fix_tool（target=项目名，issue 走 @@CONTENT@@）
              - 后端功能（action / 参数 / 逻辑）→ fix_tool 走后端
              - 前端页面（新增 / 样式 / 布局 / 交互）→ fix_tool 转 frontend_agent
              - ★ 禁止用 file_utils_api 直接改 .java / .html 源码
        0.6. 加前端页面 / 改前端 → fix_tool
              - 不要自己在后端插件里塞 HTML 字符串
              - 不要用 file_utils_api 写 www/ 下的 HTML
        1. 上方高频工具覆盖 → 直接调用
        2. 高频工具但不确定 action → 先 contract_agent.get 查契约，看支持哪些 action
        3. 低频工具或未知插件 → contract_agent.get 查契约（同一插件只查一次）
        4. 用户的需求大任务 → 先和用户确认需求，达成一致后 create_tool
        5. 一次性任务 → 用 create_tool 生成插件

        ============================================================
        信息流格式
        ============================================================
        [时间] 👤 用户消息
        [时间] 💭 AI 思考
        [时间] 🔧 调用 use_tool
        [时间] 📤 调用工具（call_start，含目标插件名）
        [时间] ✅ <插件名> 调用完成（call_end，结果在下方）
        [时间] 📊 系统消息

        ⚠️ 看到 ✅ <插件名> 调用完成，说明该工具已执行，结果已返回。
        ⚠️ 不要重复调用同一个工具。如果已有结果，直接用结果回复用户。

        ============================================================
        系统认知
        ============================================================
        - 插件在 src/plugins/，路由 = 去掉 src/plugins/ 和 .java 后缀
        - 创建/修复/删除必须用对应工具，禁止直接操作文件
        - 路径统一用正斜杠 /
        - ★ 前端页面统一放 www/{项目名}/index.html
          访问 <应用>/{项目名}/
        - ★ 后端插件只做数据处理，不返回 HTML
        - ★ 改前端用 fix_tool（target=项目名），不要用 file_utils_api
        【当前技能】段（如果存在）是你主动加载的技能说明，优先按它的指引执行。
        技能内容可能包含脚本路径，所有相对路径都相对于技能的 skill_dir。

        ============================================================
        行为准则
        ============================================================
        1. 创建/删除前，先用文本回复确认意图。
        2. create_tool 禁止自己输出代码，代码由 generator 生成。
        3. create_tool 成功后不要重复调用，创建后插件会自动热加载，查询契约后即可使用
        4. 单文件简单读写统一用 file_utils_api。
        5. 遇到高价值经验主动 add_experience。
        6. 主动用 add_task_board 建任务看板，记录当前任务的关键信息，每次推进后更新，完成后 clear。
        7. 同一插件契约查过一次后，直接用记忆，禁止重复查询。
        8. 禁止把"下一步计划"当回复输出。如果你想继续调工具，
           直接输出 JSON，不要先写"接下来测 X"、"先测 Y"这类叙述。
        9. 遇到专业领域任务，先 skill_loader.search 搜技能，有匹配就 load 后再干活。
           search 必须同时给 query 和 keywords，keywords 是数组，不能为空。

        ============================================================
        铁律（最后再强调一次）
        ============================================================
        🔴 一次只输出 1 个 JSON 或 1 段文本，禁止多个 JSON
        🔴 如果需要多个操作，分多轮：本轮一个，下一轮再一个
        🔴 高频工具照抄模板，不要自己拼结构。
        🔴 action 放 args 顶层；无 action 的插件直接写 params。
        🔴 大文本参数（experience / content / issue）一律走 @@CONTENT@@ 块。
        🔴 禁止 <think> 标签（XML 标签），think 是 JSON 字段。
        🔴 临时文件的默认工作目录：data/workspace
        🔴 不确定 action → 先查契约，不要瞎试
        🔴 回复用户时用纯文本，不要用 JSON reply
        🔴 专业领域任务先搜技能（skill_loader.search），别一上来就 create_tool

        """;

    public static String buildSystem(String sessionId) {
        return SYSTEM_PROMPT.replace("__SESSION_ID__", sessionId);
    }

    public static String buildUser(String sessionId, String context, int depth, String activeSkill) {
        List<String> parts = new ArrayList<>();
        String currentTime = java.time.LocalDateTime.now().toString().replace("T", " ").substring(0, 19);

        String experience = main_agent.loadExperience(sessionId);
        String taskboard = main_agent.loadTaskboard(sessionId);

        if (context != null && !context.isEmpty()) {
            parts.add("【信息流】\n" + context);
        }
        if (experience != null && !experience.isEmpty()) {
            parts.add("【经验背包】\n" + experience);
        }
        // ★ 新增：技能段，放在经验之后、看板之前
        if (activeSkill != null && !activeSkill.isEmpty()) {
            parts.add("【当前技能】\n" + activeSkill);
        }
        if (taskboard != null && !taskboard.isEmpty()) {
            parts.add("【任务看板】\n" + taskboard);
        }

        String hint;
        if (depth == 0) hint = "开始执行，综合分析，整理思路，逐步执行，一次只调用一次工具或者回复";
        else if (depth <= 10) hint = "执行中：检查上一步结果，继续推进。";
        else if (depth <= 20) hint = "检查：本次执行是否接近完成？是→回复结果，否→继续。";
        else if (depth <= 30) hint = "注意：已执行多步，优先考虑是否该回复用户当前进展。";
        else hint = "警告：执行深度过高接近上限，整理原因，准备收尾，及时回复用户。";

        parts.add("【当前递归执行】\n当前时间：" + currentTime + "\n执行深度：" + depth + "/" + MAX_RECURSION_DEPTH + " - " + hint + "\n");
        parts.add("🔴现在只输出一个 JSON 或用文本回复用户。不要回复你的推理过程，开始：");

        return String.join("\n\n", parts);
    }
}
