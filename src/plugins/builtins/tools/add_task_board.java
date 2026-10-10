import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * builtins/tools/add_task_board — 更新当前任务看板
 *
 * receiver 从"文件路径"推：builtins/tools/add_task_board
 *
 * action：
 *   replace 覆盖（默认）
 *   append  追加
 *   clear   清空
 *
 * 存储：data/memories/main_agent/taskboards/{session_id}_taskboard.txt
 */
public class add_task_board implements Plugin {

    private static final Path TASKBOARD_DIR =
        Path.of("data", "memories", "main_agent", "taskboards");

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "replace";

        String sessionId = str(envelop.meta.get("session_id"));
        if (sessionId.isEmpty()) {
            sessionId = str(envelop.payload.get("session_id"));
        }
        if (sessionId.isEmpty()) {
            sessionId = "default";
        }

        String content = str(envelop.payload.get("content"));

        try {
            Path file = TASKBOARD_DIR.resolve(sessionId + "_taskboard.txt");

            switch (action) {
                case "clear":
                    if (Files.exists(file)) Files.delete(file);
                    envelop.payload = result("✅ 任务看板已清空", action, 0);
                    return envelop;

                case "append":
                    if (content.isEmpty()) {
                        envelop.payload = Map.of("ok", false, "error", "缺少 content 参数");
                        return envelop;
                    }
                    Files.createDirectories(TASKBOARD_DIR);
                    if (Files.exists(file)) {
                        String old = Files.readString(file, StandardCharsets.UTF_8);
                        if (!old.isEmpty() && !old.endsWith("\n")) {
                            content = "\n" + content;
                        }
                    }
                    Files.writeString(file, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    envelop.payload = result("✅ 任务看板已追加", action, Files.size(file));
                    return envelop;

                case "replace":
                default:
                    if (content.isEmpty()) {
                        envelop.payload = Map.of("ok", false, "error", "缺少 content 参数");
                        return envelop;
                    }
                    Files.createDirectories(TASKBOARD_DIR);
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                    envelop.payload = result("✅ 任务看板已更新", action, content.length());
                    return envelop;
            }
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "保存任务看板失败: " + e.getMessage());
            return envelop;
        }
    }

    private static Map<String, Object> result(String msg, String action, long size) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("ok", true);
        r.put("message", msg);
        r.put("action", action);
        r.put("size", size);
        return r;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
