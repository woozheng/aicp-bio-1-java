import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * os/_static — 静态文件服务
 *
 * 三级补全：
 *   1. 精确路径
 *   2. 补 .html
 *   3. {path}/index.html
 *
 * 使用场景：
 *   /todo_list/          → www/todo_list/index.html
 *   /todo_list           → www/todo_list/index.html
 *   /login.html          → www/login.html
 *   /console.html        → www/console.html
 *   /assets/style.css    → www/assets/style.css
 */
public class _static implements Plugin {

    private static final Path WWW_DIR = Path.of("www");

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "serve";

        if (!"serve".equals(action)) {
            envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
            return envelop;
        }

        String filePath = envelop.payload.get("file_path") != null
            ? envelop.payload.get("file_path").toString()
            : "";

        // 从 meta 兜底（gateway 可能放这里）
        if (filePath.isEmpty() && envelop.meta.get("path") != null) {
            filePath = envelop.meta.get("path").toString().replaceFirst("^/", "");
        }

        if (filePath.isEmpty() || filePath.equals("/")) {
            filePath = "index.html";
        }

        // 安全检查
        if (!safePath(filePath)) {
            envelop.payload = Map.of("ok", false, "error", "Invalid path");
            return envelop;
        }

        // ★ 三级补全查找
        Path found = tryFiles(WWW_DIR, filePath);
        if (found == null) {
            envelop.payload = Map.of("ok", false, "error", "File not found: " + filePath);
            return envelop;
        }

        try {
            byte[] bytes = Files.readAllBytes(found);
            String contentType = guessContentType(found.toString());

            envelop.meta.put("static_content", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            envelop.meta.put("content_type", contentType);
            envelop.meta.put("file_size", bytes.length);

            envelop.payload = new HashMap<>();
            envelop.payload.put("ok", true);
            envelop.payload.put("found", true);
            envelop.payload.put("source", found.toString().replace("\\", "/"));
            envelop.payload.put("size", bytes.length);

            return envelop;
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "Read failed: " + e.getMessage());
            return envelop;
        }
    }

    // ============================================================
    // 安全检查
    // ============================================================

    static boolean safePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return true;
        if (filePath.startsWith("/") || filePath.startsWith("\\")) return false;
        if (filePath.contains("..")) return false;
        if (filePath.length() >= 2 && filePath.charAt(1) == ':') return false;
        return true;
    }

    static Path normalize(Path base, String filePath) {
        try {
            Path target = base.resolve(filePath).normalize().toAbsolutePath();
            Path baseAbs = base.normalize().toAbsolutePath();
            if (!target.startsWith(baseAbs)) return null;
            return target;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 三级补全：
     *   1. www/{path}
     *   2. www/{path}.html
     *   3. www/{path}/index.html
     *
     * 注意：path 可能以 / 结尾（如 "todo_list/"），
     * 这时第 3 步会拼成 "todo_list//index.html"，
     * normalize 会规范成 "todo_list/index.html"。
     */
    static Path tryFiles(Path base, String filePath) {
        // 1. 精确
        Path target = normalize(base, filePath);
        if (target != null && Files.isRegularFile(target)) {
            return target;
        }

        // 2. 补 .html
        target = normalize(base, filePath + ".html");
        if (target != null && Files.isRegularFile(target)) {
            return target;
        }

        // 3. 目录下 index.html
        target = normalize(base, filePath + "/index.html");
        if (target != null && Files.isRegularFile(target)) {
            return target;
        }

        return null;
    }

    // ============================================================
    // content type 推断
    // ============================================================

    private static String guessContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".xml")) return "application/xml; charset=utf-8";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".map")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }
}
