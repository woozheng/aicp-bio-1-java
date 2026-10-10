import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * os/_gateway — HTTP 入口插件
 *
 * 与 Python 版 os/_gateway.py 对齐。
 *
 * 路由：
 *   GET  /                → www/index.html（主控台）
 *   GET  /api/pages       → 扫 www/ 列页面
 *   GET  /api/list        → 插件列表
 *   GET  /api/ws_config   → WebSocket 地址
 *   GET  /api/{receiver}  → 转发给插件
 *   GET  /{path}          → 委托 os/_static serve
 *   POST /api/{receiver}  → 认证 + 转发
 *
 * 认证：
 *   - 本地请求（127.0.0.1）免认证
 *   - 同源请求（Origin == Host）免认证
 *   - 其他：调 os/_auth.verify
 *   - 根目录 HTML 未认证 → JS 跳 login.html
 */
public class _gateway implements Plugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;  // 10MB

    private HttpServer server;

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "START";

        if ("START".equals(action)) {
            int port = num(envelop.payload.getOrDefault("port", 9000));
            String host = str(envelop.payload.getOrDefault("host", "127.0.0.1"));

            try {
                start(host, port, agent);
                System.out.println("[Gateway] HTTP server listening on http://" + host + ":" + port);

                envelop.payload.put("status", "listening");
                envelop.payload.put("port", port);
                envelop.payload.put("host", host);
            } catch (Exception e) {
                envelop.payload.put("status", "failed");
                envelop.payload.put("error", e.getMessage());
            }
            return envelop;
        }

        if ("STOP".equals(action)) {
            stop();
            envelop.payload.put("status", "stopped");
            return envelop;
        }

        envelop.payload.put("error", "Unknown action: " + action);
        return envelop;
    }

    // ============================================================
    // 启动 / 停止
    // ============================================================

    private void start(String host, int port, Agent agent) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", exchange -> {
            try {
                handleRequest(exchange, agent);
            } catch (Exception e) {
                try {
                    writeJson(exchange, 500, Map.of("error", str(e.getMessage())));
                } catch (IOException ignored) {}
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ============================================================
    // 请求分发
    // ============================================================

    private void handleRequest(HttpExchange exchange, Agent agent) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // CORS 预检
        if ("OPTIONS".equals(method)) {
            writeEmpty(exchange, 200, corsHeaders());
            return;
        }

        // POST /api/{receiver}
        if ("POST".equals(method) && path.startsWith("/api/")) {
            handleApi(exchange, path.substring(5), agent);
            return;
        }

        // GET /api/{...}
        if ("GET".equals(method) && path.startsWith("/api/")) {
            handleGetApi(exchange, path.substring(5), agent);
            return;
        }

        // GET /{path}（静态文件）
        if ("GET".equals(method)) {
            String filePath = path.startsWith("/") ? path.substring(1) : path;
            handleStatic(exchange, filePath, agent);
            return;
        }

        writeJson(exchange, 405, Map.of("error", "Method not allowed"));
    }

    // ============================================================
    // GET /api/{...} —— 网关内部端点 + 插件转发
    // ============================================================

    private void handleGetApi(HttpExchange exchange, String route, Agent agent) throws IOException {
        // /api/pages — 页面列表
        if (route.equals("pages")) {
            writeJson(exchange, 200, Map.of("pages", scanPages()));
            return;
        }
        if (route.equals("upload_config")) {
            String externalUrl = "";
            Object cfg = agent.get("config");
            if (cfg instanceof Map<?, ?> cfgMap) {
                Object uploadCfg = cfgMap.get("upload");
                if (uploadCfg instanceof Map<?, ?> upMap) {
                    Object eu = upMap.get("external_url");
                    if (eu != null) externalUrl = eu.toString();
                }
            }

            String hostPart = exchange.getRequestHeaders().getFirst("Host");
            if (hostPart == null || hostPart.isEmpty()) hostPart = "127.0.0.1";
            if (hostPart.contains(":")) hostPart = hostPart.split(":")[0];

            int httpPort = exchange.getLocalAddress().getPort();
            int uploadPort = httpPort + 2;

            String proto = "http";
            String xfp = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            if ("https".equals(xfp)) proto = "https";

            String url;
            if (!externalUrl.isEmpty()) {
                url = externalUrl;
            } else {
                url = proto + "://" + hostPart + ":" + uploadPort + "/upload";
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", url);
            out.put("port", uploadPort);
            out.put("secure", "https".equals(proto));
            writeJson(exchange, 200, out);
            return;
        }
        // /api/ws_config — WebSocket 配置
        if (route.equals("ws_config")) {
            handleWsConfig(exchange, agent);
            return;
        }
        if (route.equals("backend_info")) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("language", "java");
                info.put("runtime", System.getProperty("java.version"));
                info.put("version", "1.0.0");
                info.put("protocol_version", "5.4");
                info.put("plugins_dir", "src/plugins");
                info.put("www_dir", "www");
                info.put("features", List.of(
                    "plugin", "frontend", "studio",
                    "websocket", "file_receiver",
                    "restart", "cron"
                ));
                writeJson(exchange, 200, info);
                return;
            }

        // /api/health
        if (route.equals("health")) {
            writeJson(exchange, 200, Map.of("status", "ok"));
            return;
        }

        // /api/list — 插件列表
        if (route.equals("list")) {
            Envelop env = new Envelop("os/_gateway", "os/_registry");
            env.intent = "API_CALL";
            env.payload.put("action", "list");
            env.meta.put("method", "GET");
            env.meta.put("path", route);
            Envelop result = agent.call(env);
            if (result == null) {
                writeJson(exchange, 500, Map.of("error", "no response"));
                return;
            }
            writeJson(exchange, 200, result.payload != null ? result.payload : Map.of("ok", true));
            return;
        }

        // 其他 /api/{receiver} —— 转发给插件
        Envelop env = new Envelop("os/_gateway", route);
        env.intent = "API_CALL";
        env.meta.put("method", "GET");
        env.meta.put("path", route);
        env.meta.put("token", header(exchange, "X-AICP-Token"));

        // query 参数
        String query = exchange.getRequestURI().getQuery();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    env.payload.put(kv[0], kv[1]);
                } else if (kv.length == 1) {
                    env.payload.put(kv[0], "");
                }
            }
        }

        Envelop result = agent.call(env);
        if (result == null) {
            writeJson(exchange, 500, Map.of("error", "no response"));
            return;
        }
        writeJson(exchange, 200, result.payload != null ? result.payload : Map.of("ok", true));
    }

    // ============================================================
    // /api/ws_config
    // ============================================================

    private void handleWsConfig(HttpExchange exchange, Agent agent) throws IOException {
        // 读 config 里的 websocket.external_url
        String externalUrl = "";
        Object cfg = agent.get("config");
        if (cfg instanceof Map<?, ?> cfgMap) {
            Object wsCfg = cfgMap.get("websocket");
            if (wsCfg instanceof Map<?, ?> wsMap) {
                Object eu = wsMap.get("external_url");
                if (eu != null) externalUrl = eu.toString();
            }
        }

        String hostPart = exchange.getRequestHeaders().getFirst("Host");
        if (hostPart == null || hostPart.isEmpty()) hostPart = "127.0.0.1";
        if (hostPart.contains(":")) hostPart = hostPart.split(":")[0];

        int httpPort = exchange.getLocalAddress().getPort();
        int wsPort = httpPort + 1;

        String proto = "ws";
        String xfp = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
        if ("https".equals(xfp)) proto = "wss";

        String url;
        if (!externalUrl.isEmpty()) {
            url = externalUrl;
        } else {
            url = proto + "://" + hostPart + ":" + wsPort + "/ws";
        }

        Map<String, Object> out = new HashMap<>();
        out.put("url", url);
        out.put("port", wsPort);
        out.put("secure", "wss".equals(proto));
        writeJson(exchange, 200, out);
    }

    // ============================================================
    // POST /api/{receiver} —— 认证 + 转发
    // ============================================================

    @SuppressWarnings("unchecked")
    private void handleApi(HttpExchange exchange, String route, Agent agent) throws IOException {
        // 读请求体（限制 10MB）
        byte[] rawBytes;
        try {
            rawBytes = exchange.getRequestBody().readAllBytes();
        } catch (IOException e) {
            writeJson(exchange, 500, Map.of("error", "read body failed"));
            return;
        }
        if (rawBytes.length > MAX_BODY_SIZE) {
            writeJson(exchange, 413, Map.of("error",
                "Request body too large: " + rawBytes.length + " > " + MAX_BODY_SIZE));
            return;
        }

        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

        Map<String, Object> body = new HashMap<>();
        if (!rawBody.isBlank()) {
            try {
                body = MAPPER.readValue(rawBody, Map.class);
            } catch (Exception ignored) {}
        }

        // body 里的 meta
        Object metaObj = body.get("meta");
        Map<String, Object> bodyMeta = (metaObj instanceof Map<?, ?> m)
            ? (Map<String, Object>) m : new HashMap<>();

        // body 里的 payload
        Object payloadObj = body.get("payload");
        Map<String, Object> payload = (payloadObj instanceof Map<?, ?> p)
            ? (Map<String, Object>) p : body;

        // ============================================================
        // 认证判断
        // ============================================================
        boolean needAuth = !(isLocal(exchange) || isSameOrigin(exchange));

        Map<String, Object> authPayload = null;
        if (needAuth) {
            Object[] result = verifyAuth(exchange, route, agent, bodyMeta);
            boolean ok = (boolean) result[0];
            authPayload = (Map<String, Object>) result[1];
            if (!ok) {
                writeJson(exchange, 401, Map.of("error", "Unauthorized"));
                return;
            }
        }

        // ============================================================
        // 构造 Envelop
        // ============================================================
        Envelop env = new Envelop("os/_gateway", route);
        env.intent = body.get("intent") instanceof String s ? s : "API_CALL";
        env.payload = new HashMap<>(payload);
        env.meta = new HashMap<>(bodyMeta);
        env.meta.put("token", extractToken(exchange));
        env.meta.put("method", "POST");
        env.meta.put("path", route);
        env.meta.put("raw_body", rawBody);

        if (authPayload != null) {
            env.meta.put("auth_result", authPayload);
        }

        // ============================================================
        // 转发
        // ============================================================
        Envelop result = agent.call(env);

        if (result == null) {
            writeJson(exchange, 500, Map.of("error", "no response"));
            return;
        }

        // 静态内容（meta.static_content）
        if (result.meta.get("static_content") != null) {
            String contentType = str(result.meta.getOrDefault("content_type", "application/octet-stream"));
            writeRaw(exchange, 200,
                result.meta.get("static_content").toString().getBytes(StandardCharsets.UTF_8),
                contentType);
            return;
        }

        writeJson(exchange, 200, result.payload != null ? result.payload : Map.of("ok", true));
    }

    // ============================================================
    // GET /{path} —— 静态文件
    // ============================================================

    private void handleStatic(HttpExchange exchange, String filePath, Agent agent) throws IOException {
        // 根路径 → index.html
        if (filePath.isEmpty() || filePath.equals("/")) {
            filePath = "index.html";
        }

        // 根目录 HTML 认证（login.html / 404.html 放行）
        boolean isRootFile = !filePath.contains("/");
        boolean isLoginPage = filePath.equals("login.html") || filePath.equals("404.html");

        if (filePath.endsWith(".html") && isRootFile && !isLoginPage) {
            Object[] result = verifyAuth(exchange, "__static__", agent, null);
            boolean ok = (boolean) result[0];
            if (!ok) {
                // JS 跳转（避免 API 请求被重定向）
                String redirect = "login.html?redirect=" +
                    java.net.URLEncoder.encode(exchange.getRequestURI().getPath(),
                        StandardCharsets.UTF_8);
                byte[] body = ("<script>location.href=\"/" + redirect + "\"</script>")
                    .getBytes(StandardCharsets.UTF_8);
                writeRaw(exchange, 200, body, "text/html; charset=utf-8");
                return;
            }
        }

        // 委托 os/_static
        Envelop env = new Envelop("os/_gateway", "os/_static");
        env.intent = "API_CALL";
        env.payload.put("action", "serve");
        env.payload.put("file_path", filePath);
        env.meta.put("token", extractToken(exchange));
        env.meta.put("method", "GET");
        env.meta.put("path", filePath);

        Envelop result = agent.call(env);

        if (result == null) {
            writeJson(exchange, 500, Map.of("error", "no response"));
            return;
        }

        // 静态内容
        if (result.meta.get("static_content") != null) {
            String contentType = str(result.meta.getOrDefault("content_type", "text/html; charset=utf-8"));
            writeRaw(exchange, 200,
                result.meta.get("static_content").toString().getBytes(StandardCharsets.UTF_8),
                contentType);
            return;
        }

        // 错误
        if (result.payload.get("error") != null) {
            String err = result.payload.get("error").toString();
            int status = err.contains("Forbidden") ? 403 : 404;
            writeRaw(exchange, status, err.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
            return;
        }

        writeJson(exchange, 200, result.payload != null ? result.payload : Map.of("ok", true));
    }

    // ============================================================
    // 认证
    // ============================================================

    /** 返回 [Boolean ok, Map authPayload] */
    @SuppressWarnings("unchecked")
    private Object[] verifyAuth(HttpExchange exchange, String route, Agent agent,
                                Map<String, Object> extraMeta) {
        Envelop authEnv = new Envelop("os/_gateway", "os/_auth");
        authEnv.intent = "API_CALL";
        authEnv.payload.put("action", "verify");
        authEnv.payload.put("route", route);

        authEnv.meta.put("token", extractToken(exchange));
        authEnv.meta.put("authorization", header(exchange, "Authorization"));
        authEnv.meta.put("cookie_token", getCookieToken(exchange));

        if (extraMeta != null) {
            authEnv.meta.putAll(extraMeta);
        }

        try {
            Envelop result = agent.call(authEnv);
            if (result == null) return new Object[]{false, null};
            boolean ok = Boolean.TRUE.equals(result.payload.get("ok"));
            return new Object[]{ok, result.payload};
        } catch (Exception e) {
            System.err.println("[Gateway] Auth check failed: " + e.getMessage());
            return new Object[]{false, null};
        }
    }

    /** 本地请求（loopback） */
    private boolean isLocal(HttpExchange exchange) {
        if (exchange.getRequestHeaders().getFirst("X-Forwarded-For") != null) return false;
        try {
            return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** 同源请求（Origin == Host） */
    private boolean isSameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isEmpty()) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isEmpty()) return false;
        return origin.equals("http://" + host) || origin.equals("https://" + host);
    }

    /** 提取 token：X-AICP-Token → Authorization Bearer → Cookie */
    private String extractToken(HttpExchange exchange) {
        String token = header(exchange, "X-AICP-Token");
        if (!token.isEmpty()) return token;

        String authHeader = header(exchange, "Authorization");
        if (authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return getCookieToken(exchange);
    }

    private String getCookieToken(HttpExchange exchange) {
        String cookie = header(exchange, "Cookie");
        if (cookie.isEmpty()) return "";
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("aicp_token=")) {
                return trimmed.substring("aicp_token=".length());
            }
        }
        return "";
    }

    // ============================================================
    // /api/pages —— 扫描 www/ 下的 HTML
    //
    // 规则：
    //   1. 根目录下的 .html（www/console.html）→ 系统应用
    //   2. 子目录下的 index.html（www/console/index.html）→ 用户应用
    //   3. 子目录下的非 index .html（www/foo/bar.html）→ 忽略
    //   4. index.html 和 login.html 由前端过滤，不在结果里
    // ============================================================

    private java.util.List<Map<String, Object>> scanPages() {
        java.util.List<Map<String, Object>> pages = new java.util.ArrayList<>();
        java.nio.file.Path www = java.nio.file.Path.of("www");
        if (!java.nio.file.Files.exists(www)) return pages;

        java.util.Set<String> seen = new java.util.HashSet<>();

        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(www)) {
            stream.filter(java.nio.file.Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".html"))
                .forEach(p -> {
                    java.nio.file.Path rel = www.relativize(p);
                    String relStr = rel.toString().replace("\\", "/");

                    // 只保留两种：
                    //   1. 根目录的 .html
                    //   2. 子目录的 index.html
                    boolean isRootHtml = !relStr.contains("/");
                    boolean isSubIndex = relStr.endsWith("/index.html");
                    if (!isRootHtml && !isSubIndex) return;

                    if (seen.contains(relStr)) return;
                    seen.add(relStr);

                    String name;
                    String url;
                    if (isRootHtml) {
                        name = relStr.replace(".html", "");
                        url = "/" + relStr;
                    } else {
                        String dirName = relStr.replace("/index.html", "");
                        name = dirName;
                        url = "/" + dirName + "/";
                    }

                    Map<String, Object> page = new HashMap<>();
                    page.put("path", relStr);
                    page.put("url", url);
                    page.put("name", name);
                    page.put("is_system", isRootHtml || name.startsWith("_"));
                    pages.add(page);
                });
        } catch (Exception ignored) {}

        return pages;
    }

    // ============================================================
    // HTTP 工具
    // ============================================================

    private static String header(HttpExchange exchange, String name) {
        String v = exchange.getRequestHeaders().getFirst(name);
        return v != null ? v : "";
    }

    private static void writeJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = MAPPER.writeValueAsString(data);
        writeRaw(exchange, status, json.getBytes(StandardCharsets.UTF_8),
            "application/json; charset=utf-8");
    }

    private static void writeRaw(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        for (Map.Entry<String, String> e : corsHeaders().entrySet()) {
            exchange.getResponseHeaders().set(e.getKey(), e.getValue());
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void writeEmpty(HttpExchange exchange, int status, Map<String, String> headers) throws IOException {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            exchange.getResponseHeaders().set(e.getKey(), e.getValue());
        }
        exchange.sendResponseHeaders(status, -1);
    }

    private static Map<String, String> corsHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.put("Access-Control-Allow-Headers", "Content-Type, X-AICP-Token, Authorization");
        return h;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 9000; }
    }
}
