import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * os/_websocket — WebSocket 网关插件
 *
 * 与 TS 版 os/_websocket.ts / Python 版 os/_websocket.py 对应。
 *
 * 鉴权策略（对齐 Python）：
 *   - 本地请求（127.0.0.1 / ::1）→ 跳过 auth
 *   - 其他 → 检查 token（X-AICP-Token / Cookie / query token）
 */
public class _websocket implements Plugin {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    // channel_id → Set<WebSocket>
    private final Map<String, Set<WebSocket>> channels = new ConcurrentHashMap<>();
    // WebSocket → Set<channel_id>
    private final Map<WebSocket, Set<String>> wsToChannels = new ConcurrentHashMap<>();
    // WebSocket → ws_id
    private final Map<WebSocket, String> wsId = new ConcurrentHashMap<>();

    private WsServer server;

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "START";

        switch (action) {
            case "START":
                return handleStart(envelop, agent);
            case "STOP":
                return handleStop(envelop);
            case "push":
                return handlePush(envelop);
            case "broadcast":
                return handleBroadcast(envelop);
            case "push_to_ws":
                return handlePushToWs(envelop);
            case "status":
                return handleStatus(envelop);
            case "close_channel":
                return handleCloseChannel(envelop);
            default:
                envelop.payload = Map.of("error", "Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // START / STOP
    // ============================================================

    private Envelop handleStart(Envelop envelop, Agent agent) {
        int port = num(envelop.payload.getOrDefault("port", 9001));
        String host = str(envelop.payload.getOrDefault("host", "127.0.0.1"));

        try {
            server = new WsServer(new InetSocketAddress(host, port), agent);
            server.start();
            System.out.println("[WS] WebSocket server listening on ws://" + host + ":" + port + "/ws");

            envelop.payload.put("status", "listening");
            envelop.payload.put("port", port);
            envelop.payload.put("host", host);
        } catch (Exception e) {
            envelop.payload.put("status", "failed");
            envelop.payload.put("error", e.getMessage());
        }
        return envelop;
    }

    private Envelop handleStop(Envelop envelop) {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {}
            server = null;
        }
        envelop.payload.put("status", "stopped");
        return envelop;
    }

    // ============================================================
    // push / broadcast / push_to_ws
    // ============================================================

    private Envelop handlePush(Envelop envelop) {
        String channelId = str(envelop.payload.getOrDefault("channel_id", ""));
        Object dataObj = envelop.payload.getOrDefault("data", new HashMap<>());
        if (channelId.isEmpty()) {
            envelop.payload = Map.of("error", "channel_id required");
            return envelop;
        }

        Map<String, Object> data = toMap(dataObj);
        data.put("_meta", buildMeta(envelop));

        int sent = push(channelId, data);
        envelop.payload = Map.of("ok", true, "sent", sent, "channel_id", channelId);
        return envelop;
    }

    private Envelop handleBroadcast(Envelop envelop) {
        Object dataObj = envelop.payload.getOrDefault("data", new HashMap<>());
        Map<String, Object> data = toMap(dataObj);
        data.put("_meta", buildMeta(envelop));

        int sent = broadcast(data);
        envelop.payload = Map.of("ok", true, "sent", sent);
        return envelop;
    }

    private Envelop handlePushToWs(Envelop envelop) {
        String targetWsId = str(envelop.payload.getOrDefault("ws_id", ""));
        Object dataObj = envelop.payload.getOrDefault("data", new HashMap<>());
        if (targetWsId.isEmpty()) {
            envelop.payload = Map.of("error", "ws_id required");
            return envelop;
        }

        Map<String, Object> data = toMap(dataObj);
        data.put("_meta", buildMeta(envelop));

        int sent = pushToWs(targetWsId, data);
        envelop.payload = Map.of("ok", true, "sent", sent, "ws_id", targetWsId);
        return envelop;
    }

    private Envelop handleStatus(Envelop envelop) {
        envelop.payload = getStatus();
        return envelop;
    }

    private Envelop handleCloseChannel(Envelop envelop) {
        String channelId = str(envelop.payload.getOrDefault("channel_id", ""));
        closeChannel(channelId);
        envelop.payload = Map.of("ok", true, "channel_id", channelId);
        return envelop;
    }

    // ============================================================
    // 频道管理
    // ============================================================

    private void register(WebSocket ws, String channelId) {
        channels.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(ws);
        wsToChannels.computeIfAbsent(ws, k -> ConcurrentHashMap.newKeySet()).add(channelId);
        wsId.computeIfAbsent(ws, k -> "ws_" + randomHex(6));
    }

    private void unregister(WebSocket ws) {
        unregister(ws, null);
    }

    private void unregister(WebSocket ws, String channelId) {
        if (channelId != null && !channelId.isEmpty()) {
            Set<WebSocket> set = channels.get(channelId);
            if (set != null) {
                set.remove(ws);
                if (set.isEmpty()) channels.remove(channelId);
            }
            Set<String> chs = wsToChannels.get(ws);
            if (chs != null) chs.remove(channelId);
        } else {
            Set<String> chs = wsToChannels.get(ws);
            if (chs != null) {
                for (String cid : chs) {
                    Set<WebSocket> set = channels.get(cid);
                    if (set != null) {
                        set.remove(ws);
                        if (set.isEmpty()) channels.remove(cid);
                    }
                }
            }
            wsToChannels.remove(ws);
            wsId.remove(ws);
        }
    }

    private int push(String channelId, Object data) {
        Set<WebSocket> set = channels.get(channelId);
        if (set == null) return 0;

        int sent = 0;
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return 0;
        }

        for (WebSocket ws : set) {
            try {
                if (ws.isOpen()) {
                    ws.send(json);
                    sent++;
                } else {
                    unregister(ws);
                }
            } catch (Exception e) {
                unregister(ws);
            }
        }
        return sent;
    }

    private int broadcast(Object data) {
        int sent = 0;
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return 0;
        }

        for (WebSocket ws : new ArrayList<>(wsToChannels.keySet())) {
            try {
                if (ws.isOpen()) {
                    ws.send(json);
                    sent++;
                } else {
                    unregister(ws);
                }
            } catch (Exception e) {
                unregister(ws);
            }
        }
        return sent;
    }

    private int pushToWs(String targetWsId, Object data) {
        int sent = 0;
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return 0;
        }

        for (Map.Entry<WebSocket, String> e : wsId.entrySet()) {
            if (e.getValue().equals(targetWsId)) {
                try {
                    if (e.getKey().isOpen()) {
                        e.getKey().send(json);
                        sent++;
                    } else {
                        unregister(e.getKey());
                    }
                } catch (Exception ex) {
                    unregister(e.getKey());
                }
            }
        }
        return sent;
    }

    private Map<String, Object> getStatus() {
        Map<String, Object> channelsInfo = new HashMap<>();
        for (Map.Entry<String, Set<WebSocket>> e : channels.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (WebSocket ws : e.getValue()) {
                ids.add(wsId.getOrDefault(ws, "?"));
            }
            channelsInfo.put(e.getKey(), Map.of(
                "connections", e.getValue().size(),
                "ws_ids", ids
            ));
        }
        return Map.of(
            "total_channels", channels.size(),
            "total_connections", wsToChannels.size(),
            "channels", channelsInfo
        );
    }

    private void closeChannel(String channelId) {
        Set<WebSocket> set = channels.get(channelId);
        if (set == null) return;
        for (WebSocket ws : new ArrayList<>(set)) {
            try {
                ws.close(1000, "Channel closed by server");
            } catch (Exception ignored) {}
            unregister(ws, channelId);
        }
    }

    // ============================================================
    // 内部 WebSocketServer
    // ============================================================

    private class WsServer extends WebSocketServer {
        private final Agent agent;

        WsServer(InetSocketAddress address, Agent agent) {
            super(address);
            this.agent = agent;
        }

        @Override
        public void onOpen(WebSocket ws, ClientHandshake handshake) {
            String query = handshake.getResourceDescriptor();
            String channelId = getQueryParam(query, "channel");
            if (channelId == null || channelId.isEmpty()) {
                channelId = "default_" + randomHex(6);
            }
            String token = getQueryParam(query, "token");
            if (token == null) token = "";

            // ★ 本地请求跳过 auth（对齐 Python）
            boolean isLocal = false;
            try {
                InetSocketAddress remote = ws.getRemoteSocketAddress();
                if (remote != null && remote.getAddress() != null) {
                    isLocal = remote.getAddress().isLoopbackAddress();
                }
            } catch (Exception ignored) {}

            boolean shouldSkipAuth = isLocal;

            if (!shouldSkipAuth) {
                // 非本地 → 检查 auth
                Object cfgObj = agent.get("config");
                boolean enableAuth = false;
                List<?> validTokens = List.of();
                if (cfgObj instanceof Map<?, ?> cfg) {
                    Object ea = cfg.get("enable_auth");
                    enableAuth = Boolean.TRUE.equals(ea);
                    Object vt = cfg.get("tokens");
                    if (vt instanceof List<?> l) validTokens = l;
                }

                if (enableAuth && !validTokens.isEmpty() && !validTokens.contains(token)) {
                    System.out.println("[WS] Reject (auth failed): " + ws.getRemoteSocketAddress()
                        + " token=" + (token.isEmpty() ? "(empty)" : token));
                    ws.close(4001, "Unauthorized");
                    return;
                }
            }

            register(ws, channelId);
            String wid = wsId.get(ws);

            System.out.println("[WS] Connected: " + wid + " → channel=" + channelId
                + (shouldSkipAuth ? " (local, skip auth)" : ""));

            try {
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "connected");
                msg.put("ws_id", wid);
                msg.put("channel_id", channelId);
                msg.put("timestamp", System.currentTimeMillis());
                ws.send(MAPPER.writeValueAsString(msg));
            } catch (Exception ignored) {}
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            Map<String, Object> data;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = MAPPER.readValue(text, Map.class);
                data = parsed;
            } catch (Exception e) {
                try {
                    ws.send("{\"type\":\"error\",\"message\":\"Invalid JSON\"}");
                } catch (Exception ignored) {}
                return;
            }

            String type = str(data.getOrDefault("type", ""));

            if ("envelop".equals(type)) {
                handleEnvelop(ws, data);
            } else if ("subscribe".equals(type)) {
                handleSubscribe(ws, data);
            } else if ("unsubscribe".equals(type)) {
                handleUnsubscribe(ws, data);
            } else if ("ping".equals(type)) {
                try {
                    ws.send("{\"type\":\"pong\",\"timestamp\":" + System.currentTimeMillis() + "}");
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void onClose(WebSocket ws, int code, String reason, boolean remote) {
            String wid = wsId.get(ws);
            unregister(ws);
            System.out.println("[WS] Disconnected: " + wid);
        }

        @Override
        public void onError(WebSocket ws, Exception ex) {
            if (ws != null) {
                unregister(ws);
            }
        }

        @Override
        public void onStart() {
            setConnectionLostTimeout(30);
        }

        @SuppressWarnings("unchecked")
        private void handleEnvelop(WebSocket ws, Map<String, Object> data) {
            Map<String, Object> body = data;
            Object payloadObj = data.get("payload");
            if (payloadObj instanceof Map<?, ?> m) {
                body = (Map<String, Object>) m;
            }

            Envelop env = new Envelop("os/_websocket",
                str(data.getOrDefault("receiver", "")));
            env.intent = str(body.getOrDefault("intent", "API_CALL"));
            Object bodyPayload = body.get("payload");
            if (bodyPayload instanceof Map<?, ?> p) {
                env.payload = new HashMap<>((Map<String, Object>) p);
            } else {
                env.payload = new HashMap<>(body);
            }

            Map<String, Object> meta = new HashMap<>();
            Object bodyMeta = body.get("meta");
            if (bodyMeta instanceof Map<?, ?> bm) {
                meta.putAll((Map<String, Object>) bm);
            }
            meta.put("ws_id", wsId.getOrDefault(ws, ""));
            env.meta = meta;

            Envelop result = agent.call(env);

            try {
                Map<String, Object> resp = new HashMap<>();
                resp.put("type", "envelop_result");
                resp.put("trace_id", env.traceId);
                resp.put("ok", true);
                resp.put("payload", result != null ? result.payload : Map.of());
                resp.put("timestamp", System.currentTimeMillis());
                ws.send(MAPPER.writeValueAsString(resp));
            } catch (Exception ignored) {}
        }

        private void handleSubscribe(WebSocket ws, Map<String, Object> data) {
            String channel = str(data.getOrDefault("channel", ""));
            if (channel.isEmpty()) {
                Set<String> chs = wsToChannels.get(ws);
                if (chs != null && !chs.isEmpty()) {
                    channel = chs.iterator().next();
                }
            }
            register(ws, channel);
            try {
                ws.send("{\"type\":\"subscribed\",\"channel\":\"" + channel + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
            } catch (Exception ignored) {}
        }

        private void handleUnsubscribe(WebSocket ws, Map<String, Object> data) {
            String channel = str(data.getOrDefault("channel", ""));
            if (!channel.isEmpty()) {
                unregister(ws, channel);
            }
            try {
                ws.send("{\"type\":\"unsubscribed\",\"channel\":\"" + channel + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
            } catch (Exception ignored) {}
        }

        private String getQueryParam(String resource, String key) {
            int idx = resource.indexOf('?');
            if (idx < 0) return null;
            String query = resource.substring(idx + 1);
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return kv[1];
                }
            }
            return null;
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private Map<String, Object> buildMeta(Envelop envelop) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("sender", envelop.sender);
        meta.put("intent", envelop.intent);
        meta.put("trace_id", envelop.traceId);
        meta.put("timestamp", System.currentTimeMillis() / 1000.0);
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object o) {
        if (o instanceof Map<?, ?> m) return new HashMap<>((Map<String, Object>) m);
        Map<String, Object> r = new HashMap<>();
        r.put("value", o);
        return r;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 9001; }
    }

    private static String randomHex(int n) {
        byte[] bytes = new byte[(n + 1) / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.substring(0, n);
    }
}
