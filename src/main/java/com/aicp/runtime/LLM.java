package com.aicp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * LLM — Production-grade multi-provider LLM client.
 *
 * 与 TS 版 runtime/llm.ts / Python 版 runtime/_llm.py 对应。
 *
 * 支持：
 * - 多 provider（OpenAI 兼容 API）
 * - 多 model
 * - roles（role → model 映射）
 * - 重试 + 指数退避
 * - 超时
 * - 并发控制
 * - 错误兜底
 * - 流式（chatStream）
 *
 * 用 JDK 自带的 java.net.http.HttpClient，不需要第三方 HTTP 库。
 */
public class LLM {

    // ============================================================
    // 常量
    // ============================================================

    private static final int DEFAULT_REQUEST_TIMEOUT = 60;   // 秒
    private static final int DEFAULT_STREAM_TIMEOUT = 300;   // 秒
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_MAX_CONCURRENT = 10;

    private static final List<String> ERROR_PREFIXES = List.of(
        "[LLM stream error:",
        "[LLM请求失败:",
        "[系统错误:",
        "[服务请求超时",
        "[模型返回空响应",
        "[LLM 达到最大重试次数]",
        "[LLM 未配置]",
        "[空响应]"
    );

    private static final Map<String, String> FALLBACK_MESSAGES = Map.of(
        "not_configured", "[LLM 未配置]",
        "empty_response", "[模型返回空响应，请稍后重试]",
        "timeout", "[服务请求超时，请稍后重试]",
        "max_retries", "[LLM 达到最大重试次数]",
        "system_error", "[系统错误]",
        "empty", "[空响应]"
    );

    // ============================================================
    // 流式回调接口
    // ============================================================

    @FunctionalInterface
    public interface StreamCallback {
        /**
         * @param delta    本次增量文本（可能为空）
         * @param done     是否完成
         * @param error    错误信息（null 表示无错误）
         */
        void onEvent(String delta, boolean done, String error);
    }

    // ============================================================
    // 错误检测
    // ============================================================

    public static boolean isLlmErrorString(Object text) {
        if (!(text instanceof String s)) return false;
        if (s.isEmpty()) return false;
        for (String p : ERROR_PREFIXES) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    // ============================================================
    // 内部类型
    // ============================================================

    private static class ClientEntry {
        final String name;
        final String apiKey;
        final String baseUrl;
        ClientEntry(String name, String apiKey, String baseUrl) {
            this.name = name;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }
    }

    private static class ModelConfig {
        final String clientName;
        final int maxTokens;
        final double temperature;
        final boolean supportsStreaming;
        final boolean highQuality;
        ModelConfig(String clientName, int maxTokens, double temperature,
                    boolean supportsStreaming, boolean highQuality) {
            this.clientName = clientName;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.supportsStreaming = supportsStreaming;
            this.highQuality = highQuality;
        }
    }

    private static class ChatOptions {
        boolean jsonMode = false;
        Integer maxTokens = null;
        Double temperature = null;

        ChatOptions jsonMode(boolean v) { this.jsonMode = v; return this; }
        ChatOptions maxTokens(Integer v) { this.maxTokens = v; return this; }
        ChatOptions temperature(Double v) { this.temperature = v; return this; }
    }

    // ============================================================
    // 字段
    // ============================================================

    private final Map<String, ClientEntry> clients = new HashMap<>();
    private final Map<String, String> modelToClient = new HashMap<>();
    private final Map<String, ModelConfig> modelConfigs = new HashMap<>();
    private final Map<String, String> roles = new HashMap<>();
    private String defaultModel = "gpt-3.5-turbo";

    private final int maxRetries;
    private final int requestTimeout;
    private final int streamTimeout;
    private final Semaphore semaphore;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ============================================================
    // 构造
    // ============================================================

    @SuppressWarnings("unchecked")
    public LLM(Map<String, Object> config) {
        if (config == null) config = new HashMap<>();

        Object rolesObj = config.get("roles");
        if (rolesObj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                roles.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }

        Object rolesDefault = roles.get("default");
        Object cfgDefault = config.get("default");
        if (rolesDefault != null) defaultModel = rolesDefault.toString();
        else if (cfgDefault != null) defaultModel = cfgDefault.toString();

        maxRetries = intOr(config.get("max_retries"), DEFAULT_MAX_RETRIES);
        requestTimeout = intOr(config.get("request_timeout"), DEFAULT_REQUEST_TIMEOUT);
        streamTimeout = intOr(config.get("stream_timeout"), DEFAULT_STREAM_TIMEOUT);
        semaphore = new Semaphore(intOr(config.get("max_concurrent"), DEFAULT_MAX_CONCURRENT));

        Object providersObj = config.get("providers");
        if (providersObj instanceof Map<?, ?> providers) {
            initClients((Map<String, Object>) providers);
        }

        System.out.println("[LLM] Default: " + defaultModel + " | Models: " + modelToClient.size());
    }

    @SuppressWarnings("unchecked")
    private void initClients(Map<String, Object> providers) {
        for (Map.Entry<String, Object> e : providers.entrySet()) {
            String name = e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> cfgRaw)) continue;
            Map<String, Object> cfg = (Map<String, Object>) cfgRaw;

            String apiKey = strOr(cfg.get("api_key"), "");
            String baseUrl = strOr(cfg.get("base_url"), "https://api.openai.com/v1");
            Object modelsObj = cfg.get("models");
            List<Map<String, Object>> models = new ArrayList<>();

            if (modelsObj instanceof List<?> list) {
                for (Object m : list) {
                    if (m instanceof Map<?, ?> mm) {
                        models.add((Map<String, Object>) mm);
                    }
                }
            }

            if (apiKey.isEmpty() || apiKey.startsWith("${")) {
                System.out.println("[LLM] Skip " + name + ": API key not configured");
                continue;
            }

            clients.put(name, new ClientEntry(name, apiKey, baseUrl));

            for (Map<String, Object> m : models) {
                String id = strOr(m.get("id"), "");
                if (id.isEmpty()) continue;

                int maxTokens = intOr(m.get("max_tokens"), 4096);
                double temperature = doubleOr(m.get("temperature"), 0.7);
                boolean supportsStreaming = boolOr(m.get("supports_streaming"), true);
                boolean highQuality = boolOr(m.get("high_quality"), false);

                modelConfigs.put(id, new ModelConfig(name, maxTokens, temperature, supportsStreaming, highQuality));
                modelToClient.put(id, name);

                System.out.println("[LLM] ✅ " + id + " (" + name + ")");
            }
        }

        if (!modelToClient.containsKey(defaultModel)) {
            String first = modelToClient.keySet().stream().findFirst().orElse(null);
            if (first != null) {
                System.out.println("[LLM] ⚠️ default model '" + defaultModel + "' unavailable, fallback to '" + first + "'");
                defaultModel = first;
            } else {
                System.out.println("[LLM] ❌ No available model");
            }
        }
    }

    // ============================================================
    // Model 解析
    // ============================================================

    private String resolveModel(String role) {
        if (role == null || role.isEmpty()) return defaultModel;
        String model = roles.get(role);
        if (model != null && modelToClient.containsKey(model)) return model;
        if (model != null) {
            System.out.println("[LLM] ⚠️ role '" + role + "' = '" + model + "' unavailable, fallback to default");
        }
        return defaultModel;
    }

    private ClientEntry resolveClient(String model) {
        String resolvedModel = (model != null && !model.isEmpty()) ? model : defaultModel;
        String clientName = modelToClient.get(resolvedModel);
        if (clientName != null) {
            ClientEntry c = clients.get(clientName);
            if (c != null) return c;
        }
        return clients.values().stream().findFirst().orElse(null);
    }

    // ============================================================
    // 重试
    // ============================================================

    private static double calculateBackoff(int attempt, double maxWait) {
        double wait = Math.min(Math.pow(2, attempt), maxWait);
        double jitter = Math.random() * wait * 0.5;
        return wait + jitter;
    }

    // ============================================================
    // 公开：chat
    // ============================================================

    public String chat(List<Map<String, String>> messages) {
        return chat(messages, null, null);
    }

    public String chat(List<Map<String, String>> messages, String model, String role) {
        String resolvedModel;
        if (model != null && !model.isEmpty()) resolvedModel = model;
        else if (role != null && !role.isEmpty()) resolvedModel = resolveModel(role);
        else resolvedModel = defaultModel;

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FALLBACK_MESSAGES.get("system_error") + ": interrupted";
        }
        try {
            String result = chatImpl(messages, resolvedModel, new ChatOptions());
            if (result == null || result.trim().isEmpty()) {
                return FALLBACK_MESSAGES.get("empty");
            }
            return result;
        } catch (Exception e) {
            String msg = String.valueOf(e);
            return "[系统错误: " + msg.substring(0, Math.min(100, msg.length())) + "]";
        } finally {
            semaphore.release();
        }
    }

    // ============================================================
    // 公开：chatJson
    // ============================================================

    public Map<String, Object> chatJson(List<Map<String, String>> messages) {
        return chatJson(messages, null, null);
    }

    public Map<String, Object> chatJson(List<Map<String, String>> messages, String model, String role) {
        int maxAttempts = 3;
        List<Map<String, String>> localMessages = new ArrayList<>(messages);
        String lastRaw = "";

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String raw = chat(localMessages, model, role);
            lastRaw = raw;

            if (isLlmErrorString(raw)) {
                if (attempt == maxAttempts - 1) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", raw);
                    return err;
                }
                continue;
            }

            String cleaned = extractJson(raw);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(cleaned, Map.class);
                return parsed;
            } catch (Exception e) {
                System.out.println("[LLM] JSON 解析失败 " + (attempt + 1) + ": " + e.getMessage());
                if (attempt == maxAttempts - 1) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("content", cleaned);
                    result.put("parse_error", e.getMessage());
                    return result;
                }
                localMessages.add(Map.of(
                    "role", "system",
                    "content", "请只返回有效的 JSON 格式，不要包含任何其他文本。"
                ));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", lastRaw);
        result.put("error", "max_json_attempts_exceeded");
        return result;
    }

    private static String extractJson(String raw) {
        String cleaned = raw.trim();

        if (cleaned.contains("```json")) {
            int idx = cleaned.indexOf("```json");
            String rest = cleaned.substring(idx + 7);
            int end = rest.indexOf("```");
            if (end >= 0) cleaned = rest.substring(0, end).trim();
        } else if (cleaned.contains("```")) {
            int idx = cleaned.indexOf("```");
            String rest = cleaned.substring(idx + 3);
            int end = rest.indexOf("```");
            if (end >= 0) cleaned = rest.substring(0, end).trim();
        }

        return cleaned;
    }

    // ============================================================
    // 公开：chatStream
    // ============================================================

    public void chatStream(
        List<Map<String, String>> messages,
        StreamCallback callback
    ) {
        chatStream(messages, null, null, callback);
    }

    public void chatStream(
        List<Map<String, String>> messages,
        String model,
        String role,
        StreamCallback callback
    ) {
        String resolvedModel;
        if (model != null && !model.isEmpty()) resolvedModel = model;
        else if (role != null && !role.isEmpty()) resolvedModel = resolveModel(role);
        else resolvedModel = defaultModel;

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onEvent("", true, FALLBACK_MESSAGES.get("system_error") + ": interrupted");
            return;
        }
        try {
            chatStreamImpl(messages, resolvedModel, callback);
        } finally {
            semaphore.release();
        }
    }

    private void chatStreamImpl(
        List<Map<String, String>> messages,
        String model,
        StreamCallback callback
    ) {
        ClientEntry client = resolveClient(model);
        if (client == null) {
            callback.onEvent("", true, FALLBACK_MESSAGES.get("not_configured"));
            return;
        }

        ModelConfig modelCfg = modelConfigs.get(model);

        // 不支持流式 → 降级为非流式
        // 不支持流式 → 降级为非流式
        if (modelCfg != null && !modelCfg.supportsStreaming) {
            String result;
            try {
                result = chatImpl(messages, model, new ChatOptions());
            } catch (Exception e) {
                callback.onEvent("", true, "[LLM stream error: " + e.getMessage() + "]");
                return;
            }
            if (result != null && !isLlmErrorString(result)) {
                callback.onEvent(result, true, null);
            } else {
                callback.onEvent("", true, FALLBACK_MESSAGES.get("empty"));
            }
            return;
        }

        String url = client.baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", modelCfg != null ? modelCfg.maxTokens : 4096);
        body.put("temperature", modelCfg != null ? modelCfg.temperature : 0.7);
        body.put("stream", true);

        String requestBody;
        try {
            requestBody = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            callback.onEvent("", true, "[LLM stream error: " + e.getMessage() + "]");
            return;
        }

        String lastError = "";

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                System.out.println("[LLM] 📡 stream " + (attempt + 1) + "/" + maxRetries + " → " + model);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(streamTimeout))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + client.apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

                AtomicReference<Throwable> streamError = new AtomicReference<>(null);

                HttpResponse<Stream<String>> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofLines()
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String status = String.valueOf(response.statusCode());
                    lastError = "stream failed: " + status;
                    System.out.println("[LLM] ❌ stream HTTP " + status);

                    if (response.statusCode() >= 400 && response.statusCode() < 500) {
                        callback.onEvent("", true, "[LLM stream error: HTTP " + status + "]");
                        return;
                    }
                    // 5xx 重试
                    if (attempt < maxRetries - 1) {
                        double wait = calculateBackoff(attempt, 10);
                        System.out.println("[LLM] ⏳ 等待 " + String.format("%.1f", wait) + "s 后重试...");
                        Thread.sleep((long) (wait * 1000));
                    }
                    continue;
                }

                try (Stream<String> lines = response.body()) {
                    Iterator<String> it = lines.iterator();
                    while (it.hasNext()) {
                        String line = it.next();
                        String trimmed = line.trim();
                        if (!trimmed.startsWith("data:")) continue;
                        String data = trimmed.substring(5).trim();
                        if (data.isEmpty()) continue;
                        if ("[DONE]".equals(data)) {
                            callback.onEvent("", true, null);
                            return;
                        }

                        try {
                            JsonNode parsed = MAPPER.readTree(data);
                            JsonNode deltaNode = parsed.path("choices").path(0).path("delta").path("content");
                            if (!deltaNode.isMissingNode() && !deltaNode.isNull()) {
                                String delta = deltaNode.asText();
                                if (delta != null && !delta.isEmpty()) {
                                    callback.onEvent(delta, false, null);
                                }
                            }
                        } catch (Exception ignored) {
                            // ignore malformed chunks
                        }
                    }
                }

                // 流结束但没有收到 [DONE]
                callback.onEvent("", true, null);
                return;

            } catch (java.net.http.HttpTimeoutException e) {
                System.out.println("[LLM] ⏱️ stream 超时 attempt " + (attempt + 1));
                lastError = "timeout";

            } catch (Exception e) {
                System.out.println("[LLM] ❌ stream error attempt " + (attempt + 1) + ": " + e.getMessage());
                lastError = e.getMessage();
            }

            if (attempt < maxRetries - 1) {
                double wait = calculateBackoff(attempt, 10);
                System.out.println("[LLM] ⏳ 等待 " + String.format("%.1f", wait) + "s 后重试...");
                try {
                    Thread.sleep((long) (wait * 1000));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    callback.onEvent("", true, "[LLM stream error: interrupted]");
                    return;
                }
            }
        }

        callback.onEvent("", true, "[LLM stream error: " + lastError.substring(0, Math.min(200, lastError.length())) + "]");
    }

    // ============================================================
    // 内部：非流式 chat 实现
    // ============================================================

    private String chatImpl(
        List<Map<String, String>> messages,
        String model,
        ChatOptions options
    ) throws Exception {
        ClientEntry client = resolveClient(model);
        if (client == null) {
            return FALLBACK_MESSAGES.get("not_configured");
        }

        ModelConfig modelCfg = modelConfigs.get(model);
        int maxTokens = options.maxTokens != null ? options.maxTokens
            : (modelCfg != null ? modelCfg.maxTokens : 4096);
        double temperature = options.temperature != null ? options.temperature
            : (modelCfg != null ? modelCfg.temperature : 0.7);

        String url = client.baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (options.jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        String requestBody = MAPPER.writeValueAsString(body);

        String lastError = "";

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                System.out.println("[LLM] 🔄 API call " + (attempt + 1) + "/" + maxRetries + " → " + model);
                long t0 = System.currentTimeMillis();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeout))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + client.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String text = response.body();
                    lastError = response.statusCode() + " " + text.substring(0, Math.min(200, text.length()));
                    System.out.println("[LLM] ❌ HTTP " + response.statusCode() + ": " +
                        text.substring(0, Math.min(100, text.length())));
                    if (response.statusCode() >= 400 && response.statusCode() < 500) break;
                    continue;
                }

                JsonNode data = MAPPER.readTree(response.body());
                JsonNode contentNode = data.path("choices").path(0).path("message").path("content");
                String content = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asText();
                long elapsed = System.currentTimeMillis() - t0;

                if (content != null && !content.trim().isEmpty()) {
                    System.out.println("[LLM] ✅ 成功 (" + (elapsed / 1000.0) + "s, " + content.length() + " chars)");
                    return content.trim();
                }

                System.out.println("[LLM] ⚠️ 空响应 (" + (elapsed / 1000.0) + "s)");
                lastError = "empty_response";
            } catch (java.net.http.HttpTimeoutException e) {
                System.out.println("[LLM] ⏱️ 超时 attempt " + (attempt + 1));
                lastError = "timeout";
            } catch (Exception e) {
                System.out.println("[LLM] ❌ 错误 attempt " + (attempt + 1) + ": " + e.getMessage());
                lastError = e.getMessage();
            }

            if (attempt < maxRetries - 1) {
                double wait = calculateBackoff(attempt, 10);
                System.out.println("[LLM] ⏳ 等待 " + String.format("%.1f", wait) + "s 后重试...");
                Thread.sleep((long) (wait * 1000));
            }
        }

        if ("timeout".equals(lastError)) return FALLBACK_MESSAGES.get("timeout");
        if ("empty_response".equals(lastError)) return FALLBACK_MESSAGES.get("empty_response");
        return "[LLM请求失败: " + lastError.substring(0, Math.min(200, lastError.length())) + "]";
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String strOr(Object o, String def) {
        return o != null ? o.toString() : def;
    }

    private static int intOr(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static double doubleOr(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try { return Double.parseDouble(o.toString()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean boolOr(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(o.toString());
        return def;
    }
}
