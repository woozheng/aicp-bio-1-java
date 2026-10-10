import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * builtins/control/_settings — 控制面板 API
 *
 * 与 Python 版 builtins/control/_settings.py 对齐。
 * 读写 aicp.yaml（Jackson YAML）。
 *
 * action:
 *   - get       : 读配置
 *   - set       : 改配置（key=value，支持点号路径）
 *   - list/apps : 列应用
 *   - uninstall : 卸载应用
 *   - logs      : 读日志
 *   - restart   : 重启（假成功）
 */
public class _settings implements Plugin {

    static final Path CONFIG_FILE = Path.of("aicp.yaml");

    // Jackson YAML 读写器
    private static final ObjectMapper YAML_READER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper YAML_WRITER = new ObjectMapper(
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .build()
    );

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "get";

        switch (action) {
            case "get":       return handleGet(envelop);
            case "set":       return handleSet(envelop, agent);
            case "list":
            case "apps":      return handleList(envelop);
            case "uninstall": return handleUninstall(envelop);
            case "logs":      return handleLogs(envelop);
            case "restart":   return handleRestart(envelop);
            default:
                envelop.payload = err("Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // get
    // ============================================================

    private Envelop handleGet(Envelop envelop) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", config);
        // 双兼容：展开到顶层
        for (Map.Entry<String, Object> e : config.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // set
    // ============================================================

    @SuppressWarnings("unchecked")
    private Envelop handleSet(Envelop envelop, Agent agent) {
        String key = str(envelop.payload.get("key"));
        Object value = envelop.payload.get("value");

        if (key.isEmpty()) {
            envelop.payload = err("key required");
            return envelop;
        }

        Map<String, Object> config = loadConfig();

        // ★ models.providers 直接覆盖
        if ("models.providers".equals(key)) {
            Map<String, Object> models = (Map<String, Object>) config
                .computeIfAbsent("models", k -> new LinkedHashMap<>());
            models.put("providers",
                value instanceof Map ? value : new LinkedHashMap<>());
        } else if ("models.roles.default".equals(key)) {
            Map<String, Object> models = (Map<String, Object>) config
                .computeIfAbsent("models", k -> new LinkedHashMap<>());
            Map<String, Object> roles = (Map<String, Object>) models
                .computeIfAbsent("roles", k -> new LinkedHashMap<>());
            roles.put("default", value);
        } else {
            setNested(config, key, value);
        }

        config = cleanNull(config);

        // 确保 models.providers 存在
        Map<String, Object> models = (Map<String, Object>) config
            .computeIfAbsent("models", k -> new LinkedHashMap<>());
        models.computeIfAbsent("providers", k -> new LinkedHashMap<>());

        saveConfig(config);

        // 同步到内存（如果 agent 挂了 config）
        try {
            Object cfgObj = agent.get("config");
            if (cfgObj instanceof Map<?, ?> cfgMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) cfgMap;
                if ("models.providers".equals(key)) {
                    Map<String, Object> m = (Map<String, Object>) cfg
                        .computeIfAbsent("models", k -> new LinkedHashMap<>());
                    m.put("providers", value instanceof Map ? value : new LinkedHashMap<>());
                } else {
                    setNested(cfg, key, value);
                }
            }
        } catch (Exception ignored) {}

        boolean llmReloaded = false;
        String llmError = null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("key", key);
        out.put("llm_reloaded", llmReloaded);
        out.put("llm_error", llmError);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // list / apps
    // ============================================================

    @SuppressWarnings("unchecked")
    private Envelop handleList(Envelop envelop) {
        List<Map<String, Object>> apps = new ArrayList<>();

        String[] scanDirs = {"src/plugins/builtins", "src/plugins/applications",
                             "plugins/builtins", "plugins/applications"};
        Set<String> seen = new HashSet<>();

        for (String scanDir : scanDirs) {
            Path d = Path.of(scanDir);
            if (!Files.exists(d) || !Files.isDirectory(d)) continue;

            try (var stream = Files.list(d)) {
                stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(appDir -> {
                        String name = appDir.getFileName().toString();
                        if (name.startsWith(".") || name.startsWith("_")) return;
                        if (seen.contains(name)) return;
                        seen.add(name);

                        Path appYaml = appDir.resolve("app.yaml");
                        Map<String, Object> cfg = new LinkedHashMap<>();
                        String error = "";
                        if (Files.exists(appYaml)) {
                            try {
                                String text = Files.readString(appYaml, StandardCharsets.UTF_8);
                                Object parsed = YAML_READER.readValue(text, Map.class);
                                if (parsed instanceof Map<?, ?> m) {
                                    cfg.putAll((Map<String, Object>) m);
                                }
                            } catch (Exception e) {
                                error = "app.yaml 解析失败: " + e.getMessage();
                            }
                        }

                        Map<String, Object> app = new LinkedHashMap<>();
                        app.put("id", name);
                        app.put("name", cfg.getOrDefault("name", name));
                        app.put("type", scanDir.contains("builtins") ? "builtin" : "user");
                        app.put("auto_start", cfg.getOrDefault("auto_start", false));
                        app.put("java_files", countFiles(appDir, ".java"));
                        app.put("html_files", countHtmlFiles(name));
                        app.put("deletable", !scanDir.contains("builtins"));
                        app.put("error", error);
                        apps.add(app);
                    });
            } catch (IOException ignored) {}
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("apps", apps);
        envelop.payload = out;
        return envelop;
    }

    private static long countFiles(Path dir, String ext) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.toString().endsWith(ext)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long countHtmlFiles(String appName) {
        Path www = Path.of("www");
        if (!Files.exists(www)) return 0;
        Path appWww = www.resolve(appName);
        if (!Files.exists(appWww)) return 0;
        try (var stream = Files.walk(appWww)) {
            return stream.filter(p -> p.toString().endsWith(".html")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ============================================================
    // uninstall
    // ============================================================

    private Envelop handleUninstall(Envelop envelop) {
        String appId = str(envelop.payload.get("app_id"));
        if (appId.isEmpty()) {
            envelop.payload = err("app_id required");
            return envelop;
        }
        if (Set.of("studio", "mk", "control", "system", "os").contains(appId)) {
            envelop.payload = err("Cannot uninstall system app");
            return envelop;
        }

        deleteRecursive(Path.of("src", "plugins", "applications", appId));
        deleteRecursive(Path.of("plugins", "applications", appId));
        deleteRecursive(Path.of("www", appId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("app_id", appId);
        envelop.payload = out;
        return envelop;
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ============================================================
    // logs
    // ============================================================

    private Envelop handleLogs(Envelop envelop) {
        Path logPath = Path.of("data", "gateway.log");
        int linesCount = envelop.payload.get("lines") instanceof Number n
            ? n.intValue() : 100;

        List<String> lines;
        if (Files.exists(logPath)) {
            try {
                List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
                int from = Math.max(0, all.size() - linesCount);
                lines = all.subList(from, all.size());
            } catch (IOException e) {
                lines = List.of("Read failed: " + e.getMessage());
            }
        } else {
            lines = List.of("No log file found");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("lines", lines);
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // restart
    // ============================================================

    private Envelop handleRestart(Envelop envelop) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "重启指令已接收（需外部脚本配合）");
        envelop.payload = out;
        return envelop;
    }

    // ============================================================
    // YAML 读写
    // ============================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadConfig() {
        try {
            if (!Files.exists(CONFIG_FILE)) return new LinkedHashMap<>();
            String raw = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            if (raw.isBlank()) return new LinkedHashMap<>();
            Map<String, Object> parsed = YAML_READER.readValue(raw, Map.class);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            System.err.println("[_settings] load config failed: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    static synchronized void saveConfig(Map<String, Object> config) {
        try {
            String content = YAML_WRITER.writeValueAsString(config);
            Path tmp = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, CONFIG_FILE,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[_settings] save config failed: " + e.getMessage());
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    @SuppressWarnings("unchecked")
    static void setNested(Map<String, Object> map, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> cur = map;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        if (value == null) {
            cur.remove(parts[parts.length - 1]);
        } else {
            cur.put(parts[parts.length - 1], value);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cleanNull(Map<String, Object> obj) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            Object v = e.getValue();
            if (v == null) continue;
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), cleanNull((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                List<Object> cleaned = new ArrayList<>();
                for (Object item : l) {
                    if (item == null) continue;
                    if (item instanceof Map<?, ?> im) {
                        cleaned.add(cleanNull((Map<String, Object>) im));
                    } else {
                        cleaned.add(item);
                    }
                }
                out.put(e.getKey(), cleaned);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    static String str(Object o) { return o != null ? o.toString() : ""; }

    static Map<String, Object> err(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
