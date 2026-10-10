package com.aicp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Config Loader — 读 aicp.yaml，解析环境变量，深合并默认配置
 *
 * 与 TS 版 runtime/config.ts 对应。
 */
public class Config {

    // ============================================================
    // 默认配置
    // ============================================================

    public static Map<String, Object> defaultConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("host", "0.0.0.0");
        cfg.put("port", 9000);
        cfg.put("plugins_dir", "src/plugins");
        cfg.put("data_dir", "data");
        cfg.put("static_dir", null);

        Map<String, Object> models = new HashMap<>();
        models.put("default", "gpt-3.5-turbo");
        models.put("max_retries", 3);
        models.put("request_timeout", 60);
        models.put("stream_timeout", 300);
        models.put("max_concurrent", 10);
        models.put("providers", new HashMap<>());
        cfg.put("models", models);

        cfg.put("robots", new java.util.ArrayList<>());
        cfg.put("groups", new java.util.ArrayList<>());
        return cfg;
    }

    // ============================================================
    // 环境变量解析 ${VAR}
    // ============================================================

    private static final java.util.regex.Pattern ENV_VAR =
        java.util.regex.Pattern.compile("\\$\\{(\\w+)}");

    @SuppressWarnings("unchecked")
    private static Object walkAndResolve(Object obj) {
        if (obj instanceof java.util.List<?> list) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : list) out.add(walkAndResolve(o));
            return out;
        }
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), walkAndResolve(e.getValue()));
            }
            return out;
        }
        if (obj instanceof String s) {
            return resolveEnvVars(s);
        }
        return obj;
    }

    private static String resolveEnvVars(String value) {
        java.util.regex.Matcher m = ENV_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String envValue = System.getenv(varName);
            if (envValue == null) {
                System.out.println("  ⚠️  Environment variable '" + varName + "' not set, using empty string");
                envValue = "";
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(envValue));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ============================================================
    // 深合并
    // ============================================================

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> base, Map<String, Object> override) {
        for (Map.Entry<String, Object> e : override.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();

            if (base.containsKey(key)
                && base.get(key) instanceof Map
                && value instanceof Map
                && !(base.get(key) instanceof java.util.List)
                && !(value instanceof java.util.List)) {
                deepMerge((Map<String, Object>) base.get(key), (Map<String, Object>) value);
            } else {
                base.put(key, value);
            }
        }
    }

    // ============================================================
    // 主入口
    // ============================================================

    private final Map<String, Object> data;

    public Config(Map<String, Object> data) {
        this.data = data;
    }

    public static Config load() {
        return load(null);
    }

    public static Config load(String configPath) {
        Map<String, Object> config = defaultConfig();

        Path path = configPath != null
            ? Path.of(configPath).toAbsolutePath()
            : Path.of("aicp.yaml").toAbsolutePath();

        if (Files.exists(path)) {
            System.out.println("   Loading: " + path);
            try {
                String raw = Files.readString(path);
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(raw, Map.class);
                if (parsed != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resolved = (Map<String, Object>) walkAndResolve(parsed);
                    deepMerge(config, resolved);
                }
            } catch (Exception e) {
                System.err.println("   Failed to load config: " + e.getMessage());
            }
        } else {
            System.out.println("   No config file found, using defaults");
            createDefaultConfig(path);
        }

        return new Config(config);
    }

    private static void createDefaultConfig(Path path) {
        String defaultYaml = """
            # AICP Configuration

            host: 0.0.0.0
            port: 9000

            models:
              default: gpt-3.5-turbo
              max_retries: 3
              request_timeout: 60
              stream_timeout: 300
              max_concurrent: 10
              providers:
                openai:
                  base_url: https://api.openai.com/v1
                  api_key: ${OPENAI_API_KEY}
                  models:
                    - id: gpt-3.5-turbo
                      max_tokens: 4096
                      temperature: 0.7
                      default: true
            """;
        try {
            Files.writeString(path, defaultYaml);
            System.out.println("   Created default config: " + path);
        } catch (Exception ignored) {}
    }

    // ============================================================
    // getter
    // ============================================================

    public String getString(String key, String defaultValue) {
        Object v = data.get(key);
        return v != null ? v.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object v = data.get(key);
        if (v instanceof Number n) return n.intValue();
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) {
        Object v = data.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new HashMap<>();
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public Map<String, Object> all() {
        return data;
    }
}
