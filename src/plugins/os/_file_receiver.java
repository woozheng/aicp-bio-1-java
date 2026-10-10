import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * os/_file_receiver — 大文件上传接收
 *
 * 启动独立 HTTP 服务器（port + 2），处理 multipart/form-data 上传。
 * 上传文件保存到 data/uploads/ 目录。
 *
 * action:
 *   - START：启动上传服务器
 *   - STOP：停止上传服务器
 */
public class _file_receiver implements Plugin {

    static final Path UPLOAD_DIR = Path.of("data", "uploads");

    private HttpServer server;

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "START";

        if ("START".equals(action)) {
            int port = num(envelop.payload.getOrDefault("port", 9002));
            String host = str(envelop.payload.getOrDefault("host", "127.0.0.1"));

            try {
                start(host, port);
                System.out.println("[FileReceiver] listening on http://" + host + ":" + port);
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

    private void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", exchange -> {
            try {
                handleRequest(exchange);
            } catch (Exception e) {
                try {
                    writeJson(exchange, 500, Map.of("ok", false, "error", str(e.getMessage())));
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

    private void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if ("OPTIONS".equals(method)) {
            writeEmpty(exchange, 200);
            return;
        }

        if ("POST".equals(method) && "/upload".equals(path)) {
            handleUpload(exchange);
            return;
        }

        if ("GET".equals(method) && "/health".equals(path)) {
            writeJson(exchange, 200, Map.of("status", "ok"));
            return;
        }

        writeRaw(exchange, 404, "Not found".getBytes(StandardCharsets.UTF_8),
            "text/plain; charset=utf-8");
    }

    // ============================================================
    // 上传处理
    // ============================================================

    private void handleUpload(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            writeJson(exchange, 400, Map.of("ok", false, "error", "Expected multipart/form-data"));
            return;
        }

        // 提取 boundary
        String boundary = null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                boundary = trimmed.substring("boundary=".length());
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                break;
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            writeJson(exchange, 400, Map.of("ok", false, "error", "Missing boundary"));
            return;
        }

        byte[] body = readAll(exchange.getRequestBody());
        MultipartPart filePart = parseMultipart(body, boundary);
        if (filePart == null) {
            writeJson(exchange, 400, Map.of("ok", false, "error", "No file provided"));
            return;
        }

        Files.createDirectories(UPLOAD_DIR);

        String safeName = filePart.filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = "upload.bin";
        String finalName = System.currentTimeMillis() + "_" + safeName;
        Path filePath = UPLOAD_DIR.resolve(finalName);
        Files.write(filePath, filePart.data);

        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("file_path", filePath.toString().replace("\\", "/"));
        out.put("file_name", filePart.filename);
        out.put("size", filePart.data.length);
        writeJson(exchange, 200, out);
    }

    /** 简单 multipart 解析：只取第一个 file part */
    static MultipartPart parseMultipart(byte[] body, String boundary) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        int start = indexOf(body, delim, 0);
        if (start < 0) return null;
        start += delim.length;

        while (start < body.length) {
            // 跳过 \r\n
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') {
                start += 2;
            }
            // 检查结束
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                break;
            }

            int next = indexOf(body, delim, start);
            if (next < 0) break;

            int partEnd = next;
            if (partEnd >= 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') {
                partEnd -= 2;
            }

            byte[] part = new byte[partEnd - start];
            System.arraycopy(body, start, part, 0, part.length);

            // 找 header 结束 \r\n\r\n
            int headerEnd = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), 0);
            if (headerEnd >= 0) {
                String headers = new String(part, 0, headerEnd, StandardCharsets.UTF_8);

                if (headers.toLowerCase().contains("name=\"file\"")) {
                    MultipartPart mp = new MultipartPart();
                    mp.filename = extractFilename(headers);
                    int dataStart = headerEnd + 4;
                    mp.data = new byte[part.length - dataStart];
                    System.arraycopy(part, dataStart, mp.data, 0, mp.data.length);
                    return mp;
                }
            }

            start = next + delim.length;
        }

        return null;
    }

    static String extractFilename(String headers) {
        int idx = headers.indexOf("filename=\"");
        if (idx < 0) return "upload.bin";
        int end = headers.indexOf('"', idx + 10);
        if (end < 0) return "upload.bin";
        String fn = headers.substring(idx + 10, end);
        // 去掉路径部分
        int slash = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
        if (slash >= 0) fn = fn.substring(slash + 1);
        return fn;
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    static class MultipartPart {
        String filename = "upload.bin";
        byte[] data = new byte[0];
    }

    // ============================================================
    // HTTP 工具
    // ============================================================

    private static void writeJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        writeRaw(exchange, status, json.getBytes(StandardCharsets.UTF_8),
            "application/json; charset=utf-8");
    }

    private static void writeRaw(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-AICP-Token, Authorization");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void writeEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-AICP-Token, Authorization");
        exchange.sendResponseHeaders(status, -1);
    }

    private static String str(Object o) { return o != null ? o.toString() : ""; }
    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 9002; }
    }
}
