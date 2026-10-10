import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * builtins/studio/api — Studio 项目文件管理 API
 *
 * 与 Python / TS 版对齐：
 *   - 前端使用 plugins/applications/xxx 路径
 *   - Java 实际在 src/plugins/applications/xxx
 *   - 内部做路径映射，前端不用改
 */
public class api implements Plugin {

    // Java 实际允许的目录
    private static final List<Path> ALLOWED_DIRS = List.of(
        Path.of("src", "plugins", "applications"),
        Path.of("www"),
        Path.of("src", "plugins", "_cache")
    );

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        Map<String, Object> payload = envelop.payload != null ? envelop.payload : new LinkedHashMap<>();

        // 鉴权
        String studioToken = "";
        Object cfgObj = agent.get("config");
        if (cfgObj instanceof Map<?, ?> m) {
            Object t = m.get("studio_token");
            if (t != null) studioToken = t.toString();
        }
        String token = str(payload.get("token"));
        if (studioToken.isEmpty() || !token.equals(studioToken)) {
            envelop.payload = Map.of("ok", false, "error", "无权限");
            return envelop;
        }

        String action = str(payload.get("action"));
        String appId = str(payload.get("app_id"));

        switch (action) {
            case "list_projects": return handleListProjects(envelop);
            case "list_files":    return handleListFiles(envelop, appId);
            case "read_file":     return handleReadFile(envelop, payload);
            case "write_file":    return handleWriteFile(envelop, payload);
            case "save_blocks":   return handleSaveBlocks(envelop, payload, appId);
            case "delete_file":   return handleDeleteFile(envelop, payload);
            case "get_protocol":  return handleGetProtocol(envelop);
            default:
                envelop.payload = Map.of("ok", false, "error", "未知操作: " + action);
                return envelop;
        }
    }

    // ============================================================
    // 路径映射
    // ============================================================

    /** 前端路径 → Java 实际路径 */
    private static Path frontendToReal(String frontendPath) {
        if (frontendPath.startsWith("plugins/")) {
            return Path.of("src", frontendPath);
        }
        return Path.of(frontendPath);
    }

    /** Java 实际路径 → 前端路径 */
    private static String realToFrontend(String realPath) {
        String s = realPath.replace("\\", "/");
        if (s.startsWith("src/plugins/")) {
            return s.substring("src/".length());
        }
        return s;
    }

    // ============================================================
    // list_projects
    // ============================================================

    private Envelop handleListProjects(Envelop envelop) {
        Path appsDir = Path.of("src", "plugins", "applications");
        if (!Files.exists(appsDir)) {
            envelop.payload = Map.of("ok", true, "projects", new ArrayList<>());
            return envelop;
        }

        List<String> apps = new ArrayList<>();
        try (Stream<Path> stream = Files.list(appsDir)) {
            stream.filter(Files::isDirectory)
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .forEach(p -> apps.add(p.getFileName().toString()));
        } catch (IOException ignored) {}

        Collections.sort(apps);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projects", apps);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // list_files
    // ============================================================

    private Envelop handleListFiles(Envelop envelop, String appId) {
        if (appId.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 app_id");
            return envelop;
        }

        Map<String, Object> result = new LinkedHashMap<>();

        // 后端：src/plugins/applications/{appId}
        result.put("plugins", walkTree(
            Path.of("src", "plugins", "applications", appId),
            "plugins/applications/" + appId
        ));

        // 前端：www/{appId}
        result.put("www", walkTree(
            Path.of("www", appId),
            "www/" + appId
        ));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tree", result);
        envelop.payload = out;
        return envelop;
    }

    private List<Map<String, Object>> walkTree(Path root, String frontendBase) {
        List<Map<String, Object>> tree = new ArrayList<>();
        if (!Files.exists(root)) return tree;

        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted()
                .forEach(item -> {
                    String name = item.getFileName().toString();
                    if (name.equals("__pycache__") || name.equals(".git")
                        || name.endsWith(".pyc") || name.equals("__init__.py")
                        || name.endsWith(".class")) {   // Java: 过滤 .class
                        return;
                    }

                    String rel = root.relativize(item).toString().replace("\\", "/");
                    if (rel.isEmpty()) return;

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", rel);
                    entry.put("dir", Files.isDirectory(item));
                    entry.put("path", frontendBase + "/" + rel);   // 前端路径
                    tree.add(entry);
                });
        } catch (IOException ignored) {}

        return tree;
    }

    // ============================================================
    // read_file
    // ============================================================

    private Envelop handleReadFile(Envelop envelop, Map<String, Object> payload) {
        String path = str(payload.get("path"));
        if (path.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 path");
            return envelop;
        }

        Path full = frontendToReal(path).toAbsolutePath().normalize();
        if (!isAllowed(full)) {
            envelop.payload = Map.of("ok", false, "error", "路径越权");
            return envelop;
        }
        if (!Files.exists(full) || Files.isDirectory(full)) {
            envelop.payload = Map.of("ok", false, "error", "文件不存在");
            return envelop;
        }

        try {
            String content = Files.readString(full, StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("content", content);
            envelop.payload = out;
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读文件失败: " + e.getMessage());
        }
        return envelop;
    }

    // ============================================================
    // write_file
    // ============================================================

    private Envelop handleWriteFile(Envelop envelop, Map<String, Object> payload) {
        String path = str(payload.get("path"));
        String content = str(payload.get("content"));

        if (path.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 path");
            return envelop;
        }

        Path full = frontendToReal(path).toAbsolutePath().normalize();
        if (!isAllowed(full)) {
            envelop.payload = Map.of("ok", false, "error", "路径越权");
            return envelop;
        }

        try {
            Files.createDirectories(full.getParent());
            Files.writeString(full, content, StandardCharsets.UTF_8);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", realToFrontend(full.toString()));
            envelop.payload = out;
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "写文件失败: " + e.getMessage());
        }
        return envelop;
    }

    // ============================================================
    // save_blocks
    // ============================================================

    private static final Pattern BLOCK_PATTERN = Pattern.compile(
        "===\\s*(PLUGIN|HTML|CSS|JS|APP|YAML):\\s*(\\S+)\\s*===\\s*\\n(.*?)=== END ===",
        Pattern.DOTALL
    );

    private Envelop handleSaveBlocks(Envelop envelop, Map<String, Object> payload, String appId) {
        String text = str(payload.get("text"));
        if (text.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 text");
            return envelop;
        }

        List<String> saved = new ArrayList<>();
        Matcher m = BLOCK_PATTERN.matcher(text);

        while (m.find()) {
            String filepath = m.group(2).trim();
            String code = m.group(3).trim();

            if (!appId.isEmpty()) {
                filepath = filepath.replace("{project}", appId);
            }

            // 前端路径 → Java 实际路径
            Path full;
            if (filepath.contains("/") && (filepath.startsWith("plugins/") || filepath.startsWith("www/"))) {
                full = frontendToReal(filepath);
            } else {
                boolean isWebFile = filepath.endsWith(".css") || filepath.endsWith(".js")
                    || filepath.endsWith(".yaml") || filepath.endsWith(".yml")
                    || filepath.endsWith(".json") || filepath.endsWith(".xml")
                    || filepath.endsWith(".svg") || filepath.endsWith(".html")
                    || filepath.endsWith(".htm");

                if (isWebFile) {
                    full = appId.isEmpty()
                        ? Path.of("www", filepath)
                        : Path.of("www", appId, filepath);
                } else {
                    full = appId.isEmpty()
                        ? Path.of("src", "plugins", "_cache", filepath)
                        : Path.of("src", "plugins", "applications", appId, filepath);
                }
            }

            full = full.toAbsolutePath().normalize();
            if (!isAllowed(full)) {
                System.err.println("[studio/api] 路径越权，跳过: " + full);
                continue;
            }

            try {
                Files.createDirectories(full.getParent());
                Files.writeString(full, code, StandardCharsets.UTF_8);
                saved.add(realToFrontend(full.toString()));
            } catch (IOException e) {
                System.err.println("[studio/api] 写文件失败: " + full + " → " + e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("saved", saved);
        out.put("count", saved.size());
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // delete_file
    // ============================================================

    private Envelop handleDeleteFile(Envelop envelop, Map<String, Object> payload) {
        String path = str(payload.get("path"));
        if (path.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "缺少 path");
            return envelop;
        }

        Path full = frontendToReal(path).toAbsolutePath().normalize();
        if (!isAllowed(full)) {
            envelop.payload = Map.of("ok", false, "error", "路径越权");
            return envelop;
        }

        try {
            Files.deleteIfExists(full);
            envelop.payload = Map.of("ok", true);
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "删除失败: " + e.getMessage());
        }
        return envelop;
    }

    // ============================================================
    // get_protocol
    // ============================================================

    private Envelop handleGetProtocol(Envelop envelop) {
        // Java 里协议可以放在 src/plugins/builtins/studio/aicp.md
        Path protocolPath = Path.of("src", "plugins", "builtins", "studio", "aicp.md");
        if (!Files.exists(protocolPath)) {
            envelop.payload = Map.of("ok", false, "error", "协议文件不存在");
            return envelop;
        }

        try {
            String protocol = Files.readString(protocolPath, StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("protocol", protocol);
            envelop.payload = out;
        } catch (IOException e) {
            envelop.payload = Map.of("ok", false, "error", "读协议失败: " + e.getMessage());
        }
        return envelop;
    }

    // ============================================================
    // 工具
    // ============================================================

    private static boolean isAllowed(Path full) {
        Path fullAbs = full.toAbsolutePath().normalize();
        for (Path base : ALLOWED_DIRS) {
            Path baseAbs = base.toAbsolutePath().normalize();
            if (fullAbs.startsWith(baseAbs)) return true;
        }
        return false;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
