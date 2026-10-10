import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * os/_registry — 插件注册中心
 *
 * 与 TS 版 os/_registry.ts / Python 版 os/_registry.py 对应。
 *
 * receiver 从"文件路径"推：src/plugins/os/_registry.java → os/_registry
 */
public class _registry implements Plugin {

    private static final String PLUGINS_ROOT = "src/plugins";

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "list";

        switch (action) {
            case "list":
                return handleList(envelop);
            case "get":
                return handleGet(envelop);
            case "stats":
                return handleStats(envelop);
            case "sync":
                return handleSync(envelop);
            case "refresh":
                return handleRefresh(envelop);
            default:
                envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // 处理器
    // ============================================================

    private Envelop handleList(Envelop envelop) {
        List<String> list = new ArrayList<>(Plugins.INSTANCE.keySet());
        list.sort(Comparator.naturalOrder());

        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("count", list.size());
        envelop.payload.put("plugins", list);
        envelop.payload.put("stats", Map.of(
            "total", list.size(),
            "loaded", list.size()
        ));
        return envelop;
    }

    private Envelop handleGet(Envelop envelop) {
        String name = envelop.payload.get("name") != null
            ? envelop.payload.get("name").toString()
            : "";
        if (name.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "name required");
            return envelop;
        }

        boolean loaded = Plugins.INSTANCE.containsKey(name);
        Map<String, Object> info = new HashMap<>();
        info.put("name", name);
        info.put("is_loaded", loaded);
        info.put("file_path", guessFilePath(name));

        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("plugin", info);
        return envelop;
    }

    private Envelop handleStats(Envelop envelop) {
        int loaded = Plugins.INSTANCE.size();
        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("stats", Map.of(
            "total", loaded,
            "loaded", loaded
        ));
        return envelop;
    }

    private Envelop handleSync(Envelop envelop) {
        List<Path> files = scan(Path.of(PLUGINS_ROOT));
        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("discovered", files.size());
        envelop.payload.put("stats", Map.of(
            "total", Plugins.INSTANCE.size(),
            "loaded", Plugins.INSTANCE.size()
        ));
        return envelop;
    }

    private Envelop handleRefresh(Envelop envelop) {
        List<Path> files = scan(Path.of(PLUGINS_ROOT));
        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("stats", Map.of(
            "total", Plugins.INSTANCE.size(),
            "loaded", Plugins.INSTANCE.size()
        ));
        envelop.payload.put("message", "Registry refreshed (metadata only)");
        return envelop;
    }

    // ============================================================
    // 扫描
    // ============================================================

    private List<Path> scan(Path dir) {
        List<Path> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) return false;
                    if (name.endsWith(".d.java")) return false;
                    if (name.contains(".test.")) return false;
                    if (name.contains(".spec.")) return false;
                    return name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".js");
                })
                .forEach(result::add);
        } catch (IOException e) {
            // ignore
        }
        return result;
    }

    private String guessFilePath(String receiver) {
        Path path = Path.of(PLUGINS_ROOT, receiver + ".java");
        if (Files.exists(path)) return path.toString();

        Path tsPath = Path.of(PLUGINS_ROOT, receiver + ".ts");
        if (Files.exists(tsPath)) return tsPath.toString();

        return "";
    }
}
