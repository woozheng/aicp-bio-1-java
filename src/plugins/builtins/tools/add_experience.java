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
 * builtins/tools/add_experience — 沉淀经验到经验背包
 *
 * receiver 从"文件路径"推：builtins/tools/add_experience
 *
 * action：
 *   append  追加（默认）
 *   replace 覆盖
 *   clear   清空
 *
 * 存储：data/memories/main_agent/experiences/{session_id}_backpack.txt
 */
public class add_experience implements Plugin {

    private static final Path EXPERIENCE_DIR =
        Path.of("data", "memories", "main_agent", "experiences");

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "append";

        String sessionId = str(envelop.meta.get("session_id"));
        if (sessionId.isEmpty()) {
            sessionId = str(envelop.payload.get("session_id"));
        }
        if (sessionId.isEmpty()) {
            sessionId = "default";
        }

        String experience = str(envelop.payload.get("experience"));

        try {
            Path file = EXPERIENCE_DIR.resolve(sessionId + "_backpack.txt");

            switch (action) {
                case "clear":
                    if (Files.exists(file)) Files.delete(file);
                    envelop.payload = result("✅ 经验背包已清空", action, 0);
                    return envelop;

                case "replace":
                    if (experience.isEmpty()) {
                        envelop.payload = Map.of("ok", false, "error", "缺少 experience 参数");
                        return envelop;
                    }
                    Files.createDirectories(EXPERIENCE_DIR);
                    Files.writeString(file, experience, StandardCharsets.UTF_8);
                    envelop.payload = result("✅ 经验已覆盖更新", action, experience.length());
                    return envelop;

                case "append":
                default:
                    if (experience.isEmpty()) {
                        envelop.payload = Map.of("ok", false, "error", "缺少 experience 参数");
                        return envelop;
                    }
                    Files.createDirectories(EXPERIENCE_DIR);
                    if (Files.exists(file)) {
                        String old = Files.readString(file, StandardCharsets.UTF_8);
                        if (!old.isEmpty() && !old.endsWith("\n")) {
                            experience = "\n" + experience;
                        }
                    }
                    Files.writeString(file, experience, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    long size = Files.size(file);
                    envelop.payload = result("✅ 经验已追加", action, size);
                    return envelop;
            }
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "保存经验失败: " + e.getMessage());
            return envelop;
        }
    }

    private static java.util.Map<String, Object> result(String msg, String action, long size) {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
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
