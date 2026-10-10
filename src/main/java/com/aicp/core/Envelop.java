package com.aicp.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Envelop — 唯一数据载体
 * 与 Python 版 core.py / TS 版 Envelop.ts 完全对应
 *
 * 协议字段（Tier 1）：sender, receiver, ttl, status, meta
 * 额外字段（本实现选择，Tier 3；旧代码依赖，保留）：
 *   intent, channel_id, trace_id, message_id, created_at, path_history
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Envelop {

    @JsonProperty("sender")
    public String sender = "";

    @JsonProperty("receiver")
    public String receiver = "";

    @JsonProperty("intent")
    public String intent = "";

    @JsonProperty("payload")
    public Map<String, Object> payload = new HashMap<>();

    @JsonProperty("trace_id")
    public String traceId = "";

    @JsonProperty("message_id")
    public String messageId = "";

    @JsonProperty("channel_id")
    public String channelId = "";

    @JsonProperty("ttl")
    public int ttl = 10;

    @JsonProperty("meta")
    public Map<String, Object> meta = new HashMap<>();

    @JsonProperty("created_at")
    public String createdAt = "";

    @JsonProperty("path_history")
    public List<String> pathHistory = new ArrayList<>();

    /** 协议级失败状态；空 = 无错误 */
    @JsonProperty("status")
    public String status = "";

    private static final SecureRandom RANDOM = new SecureRandom();

    public Envelop() {
        this.traceId = "tr_" + randomHex(8);
        this.messageId = "msg_" + randomHex(6);
        this.createdAt = Instant.now().toString();
    }

    public Envelop(String sender, String receiver) {
        this();
        this.sender = sender != null ? sender : "";
        this.receiver = receiver != null ? receiver : "";
    }

    public static Envelop from_dict(Map<String, Object> data) {
        Envelop env = new Envelop();
        env.sender = str(data.get("sender"));
        env.receiver = str(data.get("receiver"));
        env.intent = str(data.get("intent"));
        env.payload = map(data.get("payload"));
        env.channelId = str(data.get("channel_id"));
        env.ttl = data.get("ttl") instanceof Number n ? n.intValue() : 10;
        env.meta = map(data.get("meta"));
        env.status = str(data.get("status"));
        env.traceId = data.get("trace_id") != null ? str(data.get("trace_id")) : env.traceId;
        env.messageId = data.get("message_id") != null ? str(data.get("message_id")) : env.messageId;
        env.createdAt = data.get("created_at") != null ? str(data.get("created_at")) : env.createdAt;
        return env;
    }

    public Map<String, Object> to_dict() {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", sender);
        m.put("receiver", receiver);
        m.put("intent", intent);
        m.put("payload", payload);
        m.put("trace_id", traceId);
        m.put("message_id", messageId);
        m.put("channel_id", channelId);
        m.put("ttl", ttl);
        m.put("meta", meta);
        m.put("created_at", createdAt);
        m.put("status", status);
        return m;
    }

    @Override
    public String toString() {
        return "Envelop{sender=" + sender + ", receiver=" + receiver +
               ", ttl=" + ttl + ", status=" + status +
               ", payload=" + payload + "}";
    }

    // ---------------- helpers ----------------

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new HashMap<>();
    }

    /** 生成随机十六进制字符串，等价于 Python 的 uuid.uuid4().hex[:n] */
    private static String randomHex(int n) {
        byte[] bytes = new byte[(n + 1) / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.substring(0, n);
    }
}
