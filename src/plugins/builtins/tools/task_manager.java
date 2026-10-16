import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class task_manager implements Plugin {

    static final Path TASKS_DIR = Path.of("data", "memories", "main_agent", "tasks");
    static final Path FLOWS_BASE = Path.of("data", "memories", "main_agent", "flows");
    static final int MAX_CONCURRENT_TASKS = 5;
    static final int TASK_TIMEOUT_SECONDS = 600;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task_manager-worker");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(String session) {
        return LOCKS.computeIfAbsent(session, k -> new Object());
    }

    // ============================================================
    // 存储
    // ============================================================

    static Path tasksFile(String parentSession) {
        return TASKS_DIR.resolve(parentSession + ".json");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> loadTasks(String parentSession) {
        Path f = tasksFile(parentSession);
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            return MAPPER.readValue(raw, LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static void saveTasks(String parentSession, Map<String, Map<String, Object>> tasks) {
        try {
            Files.createDirectories(TASKS_DIR);
            Path f = tasksFile(parentSession);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            String content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tasks);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {}
    }

    static String now() {
        return Instant.now().toString();
    }

    static boolean updateTask(String parentSession, String taskId, Map<String, Object> patch) {
        synchronized (lockFor(parentSession)) {
            Map<String, Map<String, Object>> tasks = loadTasks(parentSession);
            if (!tasks.containsKey(taskId)) return false;
            tasks.get(taskId).putAll(patch);
            saveTasks(parentSession, tasks);
            return true;
        }
    }

    static int countRunning(String parentSession) {
        Map<String, Map<String, Object>> tasks = loadTasks(parentSession);
        int n = 0;
        for (Map<String, Object> t : tasks.values()) {
            if ("running".equals(str(t.get("status")))) n++;
        }
        return n;
    }

    static void checkTimeouts(String parentSession) {
        synchronized (lockFor(parentSession)) {
            Map<String, Map<String, Object>> tasks = loadTasks(parentSession);
            Instant nowI = Instant.now();
            boolean changed = false;
            for (Map<String, Object> t : tasks.values()) {
                if (!"running".equals(str(t.get("status")))) continue;
                try {
                    Instant created = Instant.parse(str(t.get("created_at")));
                    if (nowI.getEpochSecond() - created.getEpochSecond() > TASK_TIMEOUT_SECONDS) {
                        t.put("status", "timeout");
                        t.put("error", "任务超过 " + TASK_TIMEOUT_SECONDS + " 秒未完成");
                        t.put("finished_at", now());
                        changed = true;
                    }
                } catch (Exception ignored) {}
            }
            if (changed) saveTasks(parentSession, tasks);
        }
    }

    static String formatSummary(String parentSession) {
        Map<String, Map<String, Object>> tasks = loadTasks(parentSession);
        if (tasks.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        lines.add("【分身任务】");

        List<Map.Entry<String, Map<String, Object>>> sorted = new ArrayList<>(tasks.entrySet());
        sorted.sort(Comparator.comparing(e -> str(e.getValue().get("created_at"))));

        Map<String, String> icons = Map.of(
            "running", "⏳", "done", "✅", "failed", "❌",
            "timeout", "⏰", "cancelled", "🚫"
        );

        for (Map.Entry<String, Map<String, Object>> e : sorted) {
            String tid = e.getKey();
            Map<String, Object> t = e.getValue();
            String status = str(t.get("status"));
            String icon = icons.getOrDefault(status, "?");
            String desc = str(t.get("description"));
            lines.add(icon + " " + tid + ": " + desc + " [" + status + "]");
        }
        return String.join("\n", lines);
    }

    /**
     * 删除指定的 flow 文件，返回删除数量
     */
    static int deleteFlowFiles(List<String> taskIds) {
        if (!Files.exists(FLOWS_BASE)) return 0;
        int deleted = 0;
        try {
            List<Path> dateDirs = new ArrayList<>();
            try (var stream = Files.list(FLOWS_BASE)) {
                stream.filter(Files::isDirectory).forEach(dateDirs::add);
            }
            for (Path dateDir : dateDirs) {
                for (String tid : taskIds) {
                    Path flowFile = dateDir.resolve(tid + ".json");
                    if (Files.exists(flowFile)) {
                        try {
                            Files.delete(flowFile);
                            deleted++;
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    // ============================================================
    // WS 推送
    // ============================================================

    static void pushEvent(Agent agent, String sessionId, String step, String msg) {
        try {
            Envelop wsEnv = new Envelop("builtins/tools/task_manager", "os/_websocket");
            wsEnv.payload.put("action", "push");
            wsEnv.payload.put("channel_id", "pa_" + sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "progress");
            data.put("step", step);
            data.put("msg", msg);
            data.put("message", msg);
            data.put("agent_type", "task_manager");
            wsEnv.payload.put("data", data);
            agent.call(wsEnv);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // action 处理器
    // ============================================================

    static Map<String, Object> handleCreate(Agent agent, Map<String, Object> params, String sessionId) {
        String description = str(params.get("description")).trim();
        String taskText = str(params.get("task")).trim();

        if (description.isEmpty() || taskText.isEmpty()) {
            return Map.of("ok", false, "think", "缺少 description 或 task");
        }

        if (countRunning(sessionId) >= MAX_CONCURRENT_TASKS) {
            return Map.of("ok", false, "think",
                "并发任务已达上限（" + MAX_CONCURRENT_TASKS + "）。请等现有任务完成，或用 task_manager.list 查看。");
        }

        String taskId = "sub_" + randomHex(8);

        synchronized (lockFor(sessionId)) {
            Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("task_id", taskId);
            entry.put("parent_session", sessionId);
            entry.put("description", description);
            entry.put("task", taskText);
            entry.put("status", "running");
            entry.put("created_at", now());
            entry.put("finished_at", null);
            entry.put("result", null);
            entry.put("error", null);
            tasks.put(taskId, entry);
            saveTasks(sessionId, tasks);
        }

        final String finalTaskId = taskId;
        EXECUTOR.submit(() -> runSubTask(agent, sessionId, finalTaskId, taskText));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("description", description);
        data.put("status", "running");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("think",
            "✅ 分身任务已创建：" + taskId + "（" + description + "）。"
            + "任务在后台执行，完成后会自动回调你。"
            + "⚠️ 不要 list / collect 轮询，等回调即可。");
        result.put("data", data);
        result.put("_summary", formatSummary(sessionId));
        return result;
    }

    static Map<String, Object> handleSend(Agent agent, Map<String, Object> params, String sessionId) {
        String taskId = str(params.get("task_id")).trim();
        String message = str(params.get("message")).trim();

        if (taskId.isEmpty() || message.isEmpty()) {
            return Map.of("ok", false, "think", "缺少 task_id 或 message");
        }

        Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
        Map<String, Object> t = tasks.get(taskId);
        if (t == null) return Map.of("ok", false, "think", "任务不存在: " + taskId);

        // 状态改回 running
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", "running");
        patch.put("finished_at", null);
        patch.put("error", null);
        updateTask(sessionId, taskId, patch);

        final String finalTaskId = taskId;
        EXECUTOR.submit(() -> runSubTask(agent, sessionId, finalTaskId, message));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("status", "running");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("think",
            "✅ 已发送到 " + taskId + "，分身将带着记忆继续。"
            + "任务在后台执行，完成后会自动回调你。"
            + "⚠️ 不要轮询，等回调即可。");
        result.put("data", data);
        result.put("_summary", formatSummary(sessionId));
        return result;
    }

    static void runSubTask(Agent agent, String parentSession, String taskId, String taskText) {
        try {
            pushEvent(agent, parentSession, "executing", "⚡ 分身 " + taskId + " 开始执行");

            Envelop env = new Envelop("builtins/tools/task_manager", "builtins/agents/main_agent");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", taskText);
            payload.put("session_id", taskId);
            payload.put("action", "chat");
            env.payload = payload;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("session_id", taskId);
            // ★ 不再传 callback_receiver，避免双重回调
            meta.put("callback_session_id", parentSession);
            meta.put("_task_id", taskId);
            env.meta = meta;

            Envelop result = agent.call(env);

            Map<String, Object> payloadRes = result != null && result.payload != null
                ? result.payload : new LinkedHashMap<>();
            boolean ok = !Boolean.FALSE.equals(payloadRes.get("ok"));
            String error = str(payloadRes.get("error"));

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("status", ok ? "done" : "failed");
            patch.put("finished_at", now());
            patch.put("result", payloadRes);
            patch.put("error", error.isEmpty() ? null : error);
            updateTask(parentSession, taskId, patch);

            notifyParent(agent, parentSession, taskId, payloadRes);

        } catch (Exception e) {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("status", "failed");
            patch.put("finished_at", now());
            patch.put("error", "分身执行失败: " + e.getMessage());
            updateTask(parentSession, taskId, patch);
            try {
                notifyParent(agent, parentSession, taskId,
                    Map.of("ok", false, "error", e.getMessage()));
            } catch (Exception ignored) {}
        }
    }

    static void notifyParent(Agent agent, String parentSession, String taskId,
                             Map<String, Object> taskResult) {
        try {
            Envelop callback = new Envelop("builtins/tools/task_manager",
                                           "builtins/agents/main_agent");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", "");
            payload.put("action", "");
            callback.payload = payload;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("session_id", parentSession);
            meta.put("is_callback", true);
            meta.put("callback_session_id", parentSession);
            meta.put("_task_id", taskId);
            meta.put("_task_summary", formatSummary(parentSession));
            meta.put("_task_result", taskResult);
            callback.meta = meta;

            agent.call(callback);

            pushEvent(agent, parentSession, "done", "✅ 分身 " + taskId + " 完成");

        } catch (Exception ignored) {}
    }

    static Map<String, Object> handleList(Agent agent, Map<String, Object> params, String sessionId) {
        checkTimeouts(sessionId);
        Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
        String statusFilter = str(params.get("status"));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : tasks.entrySet()) {
            Map<String, Object> t = e.getValue();
            if (!statusFilter.isEmpty() && !statusFilter.equals(str(t.get("status")))) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("task_id", e.getKey());
            item.put("description", str(t.get("description")));
            item.put("status", str(t.get("status")));
            item.put("created_at", t.get("created_at"));
            item.put("finished_at", t.get("finished_at"));
            result.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", result);
        data.put("running_count", countRunning(sessionId));
        data.put("max_concurrent", MAX_CONCURRENT_TASKS);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("think", "共 " + result.size() + " 个任务（running 的在后台跑，完成后会自动回调，不要轮询）");
        r.put("data", data);
        r.put("_summary", formatSummary(sessionId));
        return r;
    }

    static Map<String, Object> handleStatus(Agent agent, Map<String, Object> params, String sessionId) {
        String taskId = str(params.get("task_id"));
        if (taskId.isEmpty()) return Map.of("ok", false, "think", "缺少 task_id");

        Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
        Map<String, Object> t = tasks.get(taskId);
        if (t == null) return Map.of("ok", false, "think", "任务不存在: " + taskId);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("think", "任务 " + taskId + " 状态: " + str(t.get("status")));
        r.put("data", t);
        return r;
    }



    static Map<String, Object> handleCollect(Agent agent, Map<String, Object> params, String sessionId) {
        String taskId = str(params.get("task_id"));
        boolean wait = Boolean.TRUE.equals(params.get("wait"));
        long timeout = params.get("timeout") instanceof Number n ? n.longValue() : 60;

        if (!taskId.isEmpty()) {
            checkTimeouts(sessionId);
            Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
            Map<String, Object> t = tasks.get(taskId);
            if (t == null) return Map.of("ok", false, "think", "任务不存在: " + taskId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("task_id", taskId);
            data.put("description", str(t.get("description")));
            data.put("status", t.get("status"));
            data.put("result", t.get("result"));
            data.put("error", t.get("error"));
            data.put("created_at", t.get("created_at"));
            data.put("finished_at", t.get("finished_at"));

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("think", "任务 " + taskId + " 状态: " + str(t.get("status")));
            r.put("data", data);
            r.put("_summary", formatSummary(sessionId));
            return r;
        }

        checkTimeouts(sessionId);

        if (wait) {
            long start = System.currentTimeMillis();
            while (true) {
                if (countRunning(sessionId) == 0) break;
                if ((System.currentTimeMillis() - start) / 1000.0 >= timeout) break;
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                checkTimeouts(sessionId);
            }
        }

        Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
        Map<String, Object> done = new LinkedHashMap<>();
        Map<String, Object> running = new LinkedHashMap<>();
        Map<String, Object> failed = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Object>> e : tasks.entrySet()) {
            String tid = e.getKey();
            Map<String, Object> t = e.getValue();
            String status = str(t.get("status"));
            if ("done".equals(status)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("description", str(t.get("description")));
                item.put("result", t.get("result"));
                done.put(tid, item);
            } else if ("running".equals(status)) {
                running.put(tid, str(t.get("description")));
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("description", str(t.get("description")));
                item.put("status", status);
                item.put("error", t.get("error"));
                failed.put(tid, item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("done", done);
        data.put("running", running);
        data.put("failed", failed);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("think", "完成 " + done.size() + " / 运行中 " + running.size() + " / 失败 " + failed.size());
        r.put("data", data);
        r.put("_summary", formatSummary(sessionId));
        return r;
    }

    static Map<String, Object> handleClear(Agent agent, Map<String, Object> params, String sessionId) {
        synchronized (lockFor(sessionId)) {
            Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
            Map<String, Map<String, Object>> kept = new LinkedHashMap<>();
            List<String> removedTids = new ArrayList<>();

            for (Map.Entry<String, Map<String, Object>> e : tasks.entrySet()) {
                String status = str(e.getValue().get("status"));
                if ("running".equals(status) || "pending".equals(status)) {
                    kept.put(e.getKey(), e.getValue());
                } else {
                    removedTids.add(e.getKey());
                }
            }

            int removed = removedTids.size();
            saveTasks(sessionId, kept);

            // ★ 删除已结束分身的 flow 文件
            int deletedFlows = deleteFlowFiles(removedTids);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("removed", removed);
            data.put("deleted_flows", deletedFlows);
            data.put("kept", kept.size());

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("think", "已清空 " + removed + " 个已结束任务（含 " + deletedFlows + " 个 flow 文件）");
            r.put("data", data);

            pushEvent(agent, sessionId, "done",
                "清理分身：" + removed + " 个记录，" + deletedFlows + " 个 flow");

            return r;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> handleDelete(Agent agent, Map<String, Object> params, String sessionId) {
        List<String> taskIds = new ArrayList<>();

        Object taskIdsObj = params.get("task_ids");
        if (taskIdsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) taskIds.add(o.toString());
            }
        }

        // 兼容单个 task_id
        if (taskIds.isEmpty()) {
            String single = str(params.get("task_id")).trim();
            if (!single.isEmpty()) taskIds.add(single);
        }

        if (taskIds.isEmpty()) {
            return Map.of("ok", false, "think", "缺少 task_ids 或 task_id");
        }

        Map<String, Map<String, Object>> tasks = loadTasks(sessionId);
        List<String> deleted = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();

        for (String rawTid : taskIds) {
            String tid = rawTid.trim();
            if (tid.isEmpty()) continue;

            if (!tasks.containsKey(tid)) {
                skipped.add(Map.of("task_id", tid, "reason", "不存在"));
                continue;
            }

            if ("running".equals(str(tasks.get(tid).get("status")))) {
                skipped.add(Map.of("task_id", tid, "reason", "正在运行，不能删除"));
                continue;
            }

            tasks.remove(tid);
            deleted.add(tid);
        }

        saveTasks(sessionId, tasks);

        // ★ 删除对应的 flow 文件
        int deletedFlows = deleteFlowFiles(deleted);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", deleted);
        data.put("deleted_flows", deletedFlows);
        data.put("skipped", skipped);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("think", "已删除 " + deleted.size() + " 个分身（含 " + deletedFlows + " 个 flow 文件）");
        r.put("data", data);
        r.put("_summary", formatSummary(sessionId));

        pushEvent(agent, sessionId, "done",
            "删除分身：" + deleted.size() + " 个记录，" + deletedFlows + " 个 flow");

        return r;
    }

    // ============================================================
    // 回调处理（保留兼容，默认不走这条路）
    // ============================================================

    static Envelop handleCallback(Envelop envelop, Agent agent) {
        Map<String, Object> meta = envelop.meta != null ? envelop.meta : new LinkedHashMap<>();
        String taskId = str(meta.get("_task_id"));
        String parentSession = str(meta.get("callback_session_id"));

        if (taskId.isEmpty() || parentSession.isEmpty()) return envelop;

        Map<String, Object> payload = envelop.payload != null ? envelop.payload : new LinkedHashMap<>();
        boolean pluginOk = !Boolean.FALSE.equals(payload.get("ok"));
        String error = str(payload.get("error"));

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", pluginOk ? "done" : "failed");
        patch.put("finished_at", now());
        patch.put("result", payload);
        patch.put("error", error.isEmpty() ? null : error);
        updateTask(parentSession, taskId, patch);

        notifyParent(agent, parentSession, taskId, payload);
        return envelop;
    }

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    @SuppressWarnings("unchecked")
    public Envelop execute(Envelop envelop, Agent agent) {
        Map<String, Object> meta = envelop.meta != null ? envelop.meta : new LinkedHashMap<>();

        // 回调优先
        if (Boolean.TRUE.equals(meta.get("is_callback"))) {
            return handleCallback(envelop, agent);
        }

        Map<String, Object> payload = envelop.payload != null ? envelop.payload : new LinkedHashMap<>();
        Map<String, Object> params = payload.get("params") instanceof Map
            ? (Map<String, Object>) payload.get("params") : new LinkedHashMap<>();
        if (params.isEmpty()) params = payload;

        String action = str(payload.getOrDefault("action", "list"));
        String sessionId = str(meta.getOrDefault("session_id", "default"));

        if (sessionId.startsWith("sub_")) {
            envelop.payload = Map.of(
                "ok", false,
                "error", "分身不能再使用 task_manager（避免无限嵌套）。请直接在当前执行线上完成任务。"
            );
            return envelop;
        }

        Map<String, Object> result;
        try {
            switch (action) {
                case "create":  result = handleCreate(agent, params, sessionId); break;
                case "send":    result = handleSend(agent, params, sessionId); break;
                case "list":    result = handleList(agent, params, sessionId); break;
                case "status":  result = handleStatus(agent, params, sessionId); break;
                case "collect": result = handleCollect(agent, params, sessionId); break;
                case "clear":   result = handleClear(agent, params, sessionId); break;
                case "delete":  result = handleDelete(agent, params, sessionId); break;
                default:
                    envelop.payload = Map.of("ok", false, "error", "未知 action: " + action);
                    return envelop;
            }
        } catch (Exception e) {
            envelop.payload = Map.of("ok", false, "error", "task_manager 异常: " + e.getMessage());
            return envelop;
        }

        Object summary = result.remove("_summary");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.getOrDefault("ok", false));
        out.put("data", result.getOrDefault("data", new LinkedHashMap<>()));
        out.put("message", result.getOrDefault("think", ""));
        if (summary != null) {
            ((Map<String, Object>) out.get("data")).put("_summary", summary);
        }
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // 工具
    // ============================================================

    static String randomHex(int n) {
        byte[] bytes = new byte[(n + 1) / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.substring(0, n);
    }

    static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
