

import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * os/_cron — 定时任务插件（系统级）
 *
 * 与 TS 版 os/_cron.ts / Python 版 os/_cron.py 对齐。
 *
 * action:
 *   - schedule：创建定时任务（interval 秒 / at_time "HH:MM"，at_time 优先）
 *   - update：更新已有任务（即时生效）
 *   - cancel：取消并删除任务
 *   - list：列出所有任务状态
 *   - restore：手动恢复持久化任务
 */
public class _cron implements Plugin {

    // ============================================================
    // 常量 & 静态状态
    // ============================================================

    static final Path DATA_DIR = Path.of("data", "os_cron");
    static final Path TASKS_FILE = DATA_DIR.resolve("tasks.json");
    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 运行中的任务：taskId → CronState */
    static final Map<String, CronState> TASKS = new ConcurrentHashMap<>();

    /** 持久化的任务配置：taskId → config */
    static volatile Map<String, Map<String, Object>> PERSISTED = new ConcurrentHashMap<>();

    /** 是否已经尝试过恢复（懒加载） */
    static volatile boolean RESTORED = false;

    /** 任务状态对象（可中断标志） */
    static class CronState {
        volatile boolean stop = false;
    }

    // ============================================================
    // 持久化
    // ============================================================

    @SuppressWarnings("unchecked")
    static void loadPersistedTasks() {
        Map<String, Map<String, Object>> out = new ConcurrentHashMap<>();
        try {
            Files.createDirectories(DATA_DIR);
            if (!Files.exists(TASKS_FILE)) {
                PERSISTED = out;
                return;
            }
            String raw = Files.readString(TASKS_FILE, StandardCharsets.UTF_8);
            Map<String, Object> parsed = MAPPER.readValue(raw, LinkedHashMap.class);
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m) {
                    out.put(e.getKey(), castMap(m));
                }
            }
        } catch (Exception ignored) {
            // 读失败就用空表
        }
        PERSISTED = out;
    }

    static synchronized void savePersistedTasks() {
        try {
            Files.createDirectories(DATA_DIR);
            Path tmp = TASKS_FILE.resolveSibling(TASKS_FILE.getFileName() + ".tmp");
            String content = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(PERSISTED);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, TASKS_FILE,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // 时间解析
    // ============================================================

    static int[] parseAtTime(String atTime) {
        String[] parts = atTime.trim().split(":");
        if (parts.length < 2) throw new IllegalArgumentException("invalid at_time: " + atTime);
        int hh = Integer.parseInt(parts[0]);
        int mm = Integer.parseInt(parts[1]);
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) {
            throw new IllegalArgumentException("invalid at_time: " + atTime);
        }
        return new int[]{hh, mm};
    }

    /** 下一个每日时刻的 epoch millis */
    static long getNextDailyTs(String atTime) {
        int[] hm = parseAtTime(atTime);
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDateTime target = now.withHour(hm[0]).withMinute(hm[1])
            .withSecond(0).withNano(0);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return target.atZone(zone).toInstant().toEpochMilli();
    }

    static String formatHeartbeatContent(String content) {
        if (content == null || content.isEmpty()) return content;
        String timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String prefix = "【心跳唤醒 " + timestamp + "】";
        if (content.startsWith("【心跳唤醒")) {
            int endIdx = content.indexOf("】");
            if (endIdx != -1) {
                return "【心跳唤醒 " + timestamp + "】" + content.substring(endIdx + 1);
            }
        }
        return prefix + content;
    }

    // ============================================================
    // 调度循环（每任务一个守护线程）
    // ============================================================

    static void startCronLoop(String taskId, Map<String, Object> config, Agent agent) {
        String atTime = config.get("at_time") != null ? str(config.get("at_time")) : null;
        int interval = config.get("interval") instanceof Number n ? n.intValue() : 0;
        String targetReceiver = str(config.get("target_receiver"));
        Map<String, Object> targetPayload =
            config.get("target_payload") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
        boolean immediate = Boolean.TRUE.equals(config.get("immediate"));

        boolean isDaily = atTime != null && !atTime.isEmpty();

        CronState state = new CronState();
        TASKS.put(taskId, state);

        Thread t = new Thread(
            () -> runLoop(taskId, state, agent, atTime, interval,
                          targetReceiver, targetPayload, immediate, isDaily),
            "cron-" + taskId
        );
        t.setDaemon(true);
        t.start();
    }

    static void runLoop(String taskId, CronState state, Agent agent,
                        String atTime, int interval, String targetReceiver,
                        Map<String, Object> targetPayload, boolean immediate,
                        boolean isDaily) {
        try {
            long nextRunTime;

            if (isDaily) {
                try {
                    nextRunTime = getNextDailyTs(atTime);
                } catch (Exception e) {
                    logError("Cron " + taskId + " parse at_time error: " + e.getMessage());
                    return;
                }
            } else {
                nextRunTime = System.currentTimeMillis();
            }

            // -------- immediate 首次执行 --------
            if (immediate) {
                fireOnce(taskId, agent, targetReceiver, targetPayload);

                if (isDaily) {
                    try {
                        nextRunTime = getNextDailyTs(atTime);
                    } catch (Exception e) {
                        return;
                    }
                } else {
                    nextRunTime += interval * 1000L;
                }
            } else {
                if (!isDaily) {
                    nextRunTime += interval * 1000L;
                }
            }

            // -------- 主循环 --------
            while (TASKS.containsKey(taskId) && !state.stop) {
                long now = System.currentTimeMillis();
                long sleepTime = nextRunTime - now;

                long jumpThreshold = !isDaily
                    ? interval * 2L * 1000L
                    : 600L * 1000L;

                if (sleepTime < -jumpThreshold) {
                    logWarn("Cron " + taskId + ": 检测到系统休眠时间跳变，重新计算调度时刻");
                    if (isDaily) {
                        try {
                            nextRunTime = getNextDailyTs(atTime);
                        } catch (Exception e) {
                            break;
                        }
                    } else {
                        nextRunTime = System.currentTimeMillis() + interval * 1000L;
                    }
                    sleepTime = nextRunTime - System.currentTimeMillis();
                }

                if (sleepTime > 0) {
                    if (!sleepInterruptible(sleepTime, state)) break;
                }

                if (!TASKS.containsKey(taskId) || state.stop) break;

                // 执行一次
                fireOnce(taskId, agent, targetReceiver, targetPayload);

                // 计算下一次
                if (isDaily) {
                    try {
                        nextRunTime = getNextDailyTs(atTime);
                    } catch (Exception e) {
                        logError("Cron " + taskId + ": at_time 解析失败，退出循环");
                        break;
                    }
                } else {
                    nextRunTime += interval * 1000L;
                }
            }
        } finally {
            TASKS.remove(taskId);
        }
    }

    /** 触发一次调用：把 target_payload 发送给 target_receiver */
    static void fireOnce(String taskId, Agent agent, String targetReceiver,
                         Map<String, Object> targetPayload) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(targetPayload);
            Object contentObj = payload.get("content");
            if (contentObj != null) {
                payload.put("content", formatHeartbeatContent(contentObj.toString()));
            }
            Envelop env = new Envelop("os/_cron", targetReceiver);
            env.payload = payload;
            agent.call(env);
        } catch (Exception e) {
            logError("Cron " + taskId + " fire error: " + e.getMessage());
        }
    }

    /** 可中断睡眠：每 100ms 检查一次 stop，返回 true 表示正常睡完 */
    static boolean sleepInterruptible(long ms, CronState state) {
        long endTime = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < endTime && !state.stop) {
            long remaining = Math.min(100L, endTime - System.currentTimeMillis());
            try {
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !state.stop;
    }

    // ============================================================
    // 恢复
    // ============================================================

    static void restoreTasks(Agent agent) {
        loadPersistedTasks();
        for (Map.Entry<String, Map<String, Object>> e : PERSISTED.entrySet()) {
            if (!TASKS.containsKey(e.getKey())) {
                try {
                    startCronLoop(e.getKey(), e.getValue(), agent);
                    logInfo("[os/_cron] 恢复任务: " + e.getKey());
                } catch (Exception ex) {
                    logError("[os/_cron] 恢复任务 " + e.getKey() + " 失败: " + ex.getMessage());
                }
            }
        }
    }

    // ============================================================
    // 主入口
    // ============================================================

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        // Envelop 里 payload/meta 永远是 new HashMap<>()，不用判 null
        Map<String, Object> payload = envelop.payload;

        // 懒加载：第一次调用时尝试恢复持久化任务
        if (!RESTORED && Files.exists(TASKS_FILE)) {
            RESTORED = true;
            restoreTasks(agent);
        }

        String action = str(payload.get("action"));

        switch (action) {
            case "schedule": return schedule(envelop, agent);
            case "update":   return update(envelop, agent);
            case "cancel":   return cancel(envelop, agent);
            case "list":     return list(envelop, agent);
            case "restore":
                restoreTasks(agent);
                envelop.payload = okOf(Map.of("restored", TASKS.size()));
                return envelop;
            default:
                envelop.payload = errOf("Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // schedule
    // ============================================================

    @SuppressWarnings("unchecked")
    static Envelop schedule(Envelop envelop, Agent agent) {
        Map<String, Object> p = envelop.payload;

        String taskId = str(p.get("task_id"));
        Object intervalObj = p.get("interval");
        String atTime = p.get("at_time") != null ? str(p.get("at_time")) : null;
        String targetReceiver = str(p.get("target_receiver"));
        Map<String, Object> targetPayload =
            p.get("target_payload") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
        boolean immediate = Boolean.TRUE.equals(p.get("immediate"));
        boolean persisted = !Boolean.FALSE.equals(p.get("persisted"));  // 默认 true

        // -------- 参数校验 --------
        if (taskId.isEmpty()) {
            envelop.payload = errOf("task_id is required");
            return envelop;
        }
        if (targetReceiver.isEmpty()) {
            envelop.payload = errOf("target_receiver is required");
            return envelop;
        }
        if (intervalObj == null && atTime == null) {
            envelop.payload = errOf("must provide interval (seconds) or at_time (HH:MM)");
            return envelop;
        }
        if (!targetPayload.containsKey("content")) {
            Map<String, Object> out = errOf("target_payload must contain 'content' field");
            ((Map<String, Object>) out).put("example", Map.of(
                "session_id", "your_session_id",
                "content", "【定时任务】执行具体操作描述"
            ));
            envelop.payload = out;
            return envelop;
        }
        if (!targetPayload.containsKey("session_id")) {
            Map<String, Object> out = errOf("target_payload must contain 'session_id' field");
            out.put("example", Map.of(
                "session_id", "your_session_id",
                "content", "【定时任务】执行具体操作描述"
            ));
            envelop.payload = out;
            return envelop;
        }

        Integer interval = null;
        if (intervalObj != null) {
            try {
                interval = Integer.parseInt(intervalObj.toString());
                if (interval <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                envelop.payload = errOf("invalid interval: " + intervalObj + ", must be positive integer");
                return envelop;
            }
        }

        if (atTime != null) {
            try {
                parseAtTime(atTime);
            } catch (Exception e) {
                envelop.payload = errOf("invalid at_time format: " + atTime + ", expected HH:MM");
                return envelop;
            }
        }

        if (TASKS.containsKey(taskId)) {
            envelop.payload = errOf("task '" + taskId + "' already exists");
            return envelop;
        }

        // -------- 补 token --------
        String token = str(envelop.meta.get("session_id"));
        if (token.isEmpty()) token = str(envelop.meta.get("token"));
        if (!targetPayload.containsKey("session_id")) {
            targetPayload.put("session_id", token);
        }

        // -------- 存配置 --------
        Map<String, Object> taskConfig = new LinkedHashMap<>();
        if (interval != null) taskConfig.put("interval", interval);
        if (atTime != null) taskConfig.put("at_time", atTime);
        taskConfig.put("target_receiver", targetReceiver);
        taskConfig.put("target_payload", targetPayload);
        taskConfig.put("token", token);
        taskConfig.put("immediate", immediate);

        if (persisted) {
            PERSISTED.put(taskId, taskConfig);
            savePersistedTasks();
        }

        startCronLoop(taskId, taskConfig, agent);

        String scheduleMode = atTime != null ? "daily" : "interval";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("task_id", taskId);
        out.put("schedule_mode", scheduleMode);
        out.put("interval", interval);
        out.put("at_time", atTime);
        out.put("immediate", immediate);
        out.put("persisted", persisted);
        out.put("target_receiver", targetReceiver);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // update
    // ============================================================

    static Envelop update(Envelop envelop, Agent agent) {
        Map<String, Object> p = envelop.payload;
        String taskId = str(p.get("task_id"));

        if (taskId.isEmpty()) {
            envelop.payload = errOf("task_id is required");
            return envelop;
        }
        if (!PERSISTED.containsKey(taskId) && !TASKS.containsKey(taskId)) {
            envelop.payload = errOf("task '" + taskId + "' not found");
            return envelop;
        }

        Map<String, Object> oldConfig = PERSISTED.getOrDefault(taskId, new LinkedHashMap<>());
        Map<String, Object> newConfig = new LinkedHashMap<>(oldConfig);

        if (p.containsKey("interval")) {
            try {
                newConfig.put("interval", Integer.parseInt(p.get("interval").toString()));
            } catch (NumberFormatException ignored) {}
        }
        if (p.containsKey("at_time")) newConfig.put("at_time", p.get("at_time"));
        if (p.containsKey("target_receiver")) newConfig.put("target_receiver", p.get("target_receiver"));
        if (p.containsKey("target_payload") && p.get("target_payload") instanceof Map<?, ?> m) {
            Map<String, Object> merged = new LinkedHashMap<>();
            Object oldTp = oldConfig.get("target_payload");
            if (oldTp instanceof Map<?, ?> oldM) merged.putAll(castMap(oldM));
            merged.putAll(castMap(m));
            newConfig.put("target_payload", merged);
        }
        if (p.containsKey("immediate")) newConfig.put("immediate", p.get("immediate"));

        boolean persisted = !Boolean.FALSE.equals(p.get("persisted"));

        // 销毁旧循环
        CronState old = TASKS.get(taskId);
        if (old != null) {
            old.stop = true;
            TASKS.remove(taskId);
        }

        if (persisted) {
            PERSISTED.put(taskId, newConfig);
            savePersistedTasks();
        } else {
            PERSISTED.remove(taskId);
            savePersistedTasks();
        }

        startCronLoop(taskId, newConfig, agent);

        Map<String, Object> updated = new LinkedHashMap<>();
        updated.put("schedule_mode", newConfig.containsKey("at_time") ? "daily" : "interval");
        updated.put("interval", newConfig.get("interval"));
        updated.put("at_time", newConfig.get("at_time"));
        updated.put("target_receiver", newConfig.get("target_receiver"));
        updated.put("immediate", Boolean.TRUE.equals(newConfig.get("immediate")));
        updated.put("persisted", persisted);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("task_id", taskId);
        out.put("updated", updated);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // cancel
    // ============================================================

    static Envelop cancel(Envelop envelop, Agent agent) {
        String taskId = str(envelop.payload.get("task_id"));

        CronState state = TASKS.get(taskId);
        if (state == null) {
            envelop.payload = errOf("task '" + taskId + "' not found");
            return envelop;
        }

        state.stop = true;
        TASKS.remove(taskId);

        boolean persistedRemoved = false;
        if (PERSISTED.containsKey(taskId)) {
            PERSISTED.remove(taskId);
            savePersistedTasks();
            persistedRemoved = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("task_id", taskId);
        out.put("persisted_removed", persistedRemoved);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // list
    // ============================================================

    static Envelop list(Envelop envelop, Agent agent) {
        Map<String, Object> tasksInfo = new LinkedHashMap<>();

        List<String> ids = new ArrayList<>(TASKS.keySet());
        Collections.sort(ids);

        for (String tid : ids) {
            Map<String, Object> config = PERSISTED.getOrDefault(tid, new LinkedHashMap<>());
            Map<String, Object> targetPayload =
                config.get("target_payload") instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();

            Map<String, Object> payloadPreview = new LinkedHashMap<>();
            if (targetPayload.containsKey("session_id"))
                payloadPreview.put("session_id", targetPayload.get("session_id"));
            if (targetPayload.containsKey("content")) {
                String content = str(targetPayload.get("content"));
                if (content.length() > 100) content = content.substring(0, 100) + "...";
                payloadPreview.put("content", content);
            }
            if (targetPayload.containsKey("action"))
                payloadPreview.put("action", targetPayload.get("action"));

            String atTimeVal = config.get("at_time") != null ? str(config.get("at_time")) : null;
            String scheduleMode = atTimeVal != null ? "daily" : "interval";

            CronState st = TASKS.get(tid);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("running", st != null && !st.stop);
            info.put("schedule_mode", scheduleMode);
            info.put("interval", config.get("interval"));
            info.put("at_time", atTimeVal);
            info.put("target_receiver", config.getOrDefault("target_receiver", "?"));
            info.put("immediate", Boolean.TRUE.equals(config.get("immediate")));
            info.put("persisted", PERSISTED.containsKey(tid));
            info.put("payload", payloadPreview);
            info.put("task_id", tid);

            tasksInfo.put(tid, info);
        }

        int total = tasksInfo.size();
        long runningCount = tasksInfo.values().stream()
            .filter(v -> Boolean.TRUE.equals(((Map<?, ?>) v).get("running")))
            .count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", tasksInfo);
        data.put("total", total);
        data.put("running", runningCount);
        data.put("stopped", total - runningCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", data);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // 工具
    // ============================================================

    static String str(Object o) { return o != null ? o.toString() : ""; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) r.put(String.valueOf(e.getKey()), e.getValue());
        return r;
    }

    static Map<String, Object> okOf(Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        if (extra != null) m.putAll(extra);
        return m;
    }

    static Map<String, Object> errOf(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }

    static void logInfo(String msg)  { System.out.println("[cron] " + msg); }
    static void logWarn(String msg)  { System.err.println("[cron] WARN " + msg); }
    static void logError(String msg) { System.err.println("[cron] ERROR " + msg); }
}
