import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * os/_auth — 认证插件
 *
 * 与 TS 版 os/_auth.ts / Python 版 os/_auth.py 对应。
 *
 * receiver 从"文件路径"推：src/plugins/os/_auth.java → os/_auth
 *
 * 支持 enable_auth 开关：
 *   - 关闭：所有请求放行
 *   - 开启：验证 X-AICP-Token / Authorization Bearer / Cookie
 */
public class _auth implements Plugin {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Set<String> PUBLIC_ROUTES = Set.of(
        "auth/login",
        "ws_config",
        "health"
    );

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "verify";

        switch (action) {
            case "verify":
                return verify(envelop, agent);
            case "login":
                return login(envelop, agent);
            case "logout":
                return logout(envelop, agent);
            case "generate_app_token":
                return generateAppToken(envelop, agent);
            default:
                envelop.payload = Map.of("error", "Unknown action: " + action);
                return envelop;
        }
    }

    // ============================================================
    // verify
    // ============================================================

    private Envelop verify(Envelop envelop, Agent agent) {
        // 来自子目录应用的请求，直接放行
        if (Boolean.TRUE.equals(envelop.meta.get("is_app_request"))) {
            envelop.payload = Map.of("ok", true, "message", "App request allowed");
            return envelop;
        }

        Map<String, Object> config = getConfig(agent);
        boolean enableAuth = Boolean.TRUE.equals(config.get("enable_auth"));
        if (!enableAuth) {
            envelop.payload = Map.of("ok", true, "message", "Auth disabled");
            return envelop;
        }

        String route = envelop.payload.get("route") != null
            ? envelop.payload.get("route").toString()
            : envelop.receiver;

        // 公开路由
        if (PUBLIC_ROUTES.contains(route)) {
            envelop.payload = Map.of("ok", true);
            return envelop;
        }

        // 提取 token
        String token = str(envelop.meta.get("token"));
        if (token.isEmpty()) {
            String authHeader = str(envelop.meta.get("authorization"));
            if (authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
        }
        if (token.isEmpty()) {
            token = str(envelop.meta.get("cookie_token"));
        }

        // 验证 token（config.tokens 是 List<String>）
        Set<String> validTokens = new HashSet<>();
        Object tokensObj = config.get("tokens");
        if (tokensObj instanceof List<?> list) {
            for (Object t : list) {
                if (t != null) validTokens.add(t.toString());
            }
        }

        if (!token.isEmpty() && validTokens.contains(token)) {
            envelop.payload = Map.of("ok", true);
            return envelop;
        }

        // 验证应用 token
        if (!token.isEmpty()) {
            String secret = config.get("app_secret") != null
                ? config.get("app_secret").toString()
                : "aicp_default_secret";

            for (String appName : listAppNames()) {
                String expected = generateAppTokenValue(appName, secret);
                if (timingSafeEqual(token, expected)) {
                    envelop.meta.put("app_name", appName);
                    envelop.meta.put("is_app_request", true);
                    envelop.payload = Map.of("ok", true, "app", appName);
                    return envelop;
                }
            }
        }

        envelop.payload = Map.of("ok", false, "error", "Unauthorized");
        return envelop;
    }

    // ============================================================
    // login / logout
    // ============================================================

    @SuppressWarnings("unchecked")
    private Envelop login(Envelop envelop, Agent agent) {
        String username = str(envelop.payload.get("username"));
        String password = str(envelop.payload.get("password"));

        if (username.isEmpty() || password.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "Missing username or password");
            return envelop;
        }

        Map<String, Object> config = getConfig(agent);
        Object usersObj = config.get("users");
        Map<String, Object> users = new HashMap<>();
        if (usersObj instanceof Map<?, ?> m) {
            users.putAll((Map<String, Object>) m);
        }

        if (users.containsKey(username)) {
            String stored = str(users.get(username));
            if (stored.equals(password) || verifyPassword(password, stored)) {
                String secret = config.get("token_secret") != null
                    ? config.get("token_secret").toString()
                    : "aicp_session_secret";
                String token = generateSessionToken(username, secret);
                envelop.payload = Map.of("ok", true, "token", token, "username", username);
            } else {
                envelop.payload = Map.of("ok", false, "error", "Invalid credentials");
            }
        } else {
            envelop.payload = Map.of("ok", false, "error", "Invalid credentials");
        }

        return envelop;
    }

    private Envelop logout(Envelop envelop, Agent agent) {
        envelop.payload = Map.of("ok", true, "message", "Logged out");
        return envelop;
    }

    private Envelop generateAppToken(Envelop envelop, Agent agent) {
        String appName = str(envelop.payload.get("app_name"));
        if (appName.isEmpty()) {
            envelop.payload = Map.of("ok", false, "error", "Missing app_name");
            return envelop;
        }

        Map<String, Object> config = getConfig(agent);
        String secret = config.get("app_secret") != null
            ? config.get("app_secret").toString()
            : "aicp_default_secret";

        String token = generateAppTokenValue(appName, secret);
        envelop.payload = Map.of("ok", true, "app_name", appName, "token", token);
        return envelop;
    }

    // ============================================================
    // 工具函数
    // ============================================================

    /** HMAC-MD5(appName, secret) 的 hex 前 16 位 */
    private static String generateAppTokenValue(String appName, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacMD5"));
            byte[] hash = mac.doFinal(appName.getBytes(StandardCharsets.UTF_8));
            String hex = bytesToHex(hash);
            return hex.substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    /** HMAC-SHA256(username:nonce, secret) 的 hex 前 32 位 */
    private static String generateSessionToken(String username, String secret) {
        byte[] nonceBytes = new byte[8];
        RANDOM.nextBytes(nonceBytes);
        String nonce = bytesToHex(nonceBytes);

        String data = username + ":" + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String signature = bytesToHex(hash).substring(0, 32);
            return "tok_" + username + "_" + signature;
        } catch (Exception e) {
            return "";
        }
    }

    /** 校验密码：支持 "sha256:xxx" 格式 */
    private static boolean verifyPassword(String password, String stored) {
        if (stored.startsWith("sha256:")) {
            String expected = stored.substring(7);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
                String actual = bytesToHex(hash);
                return timingSafeEqual(actual, expected);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** 时间安全比较（防时序攻击） */
    private static boolean timingSafeEqual(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** 列出 www/ 下的子目录（应用名） */
    private static List<String> listAppNames() {
        List<String> result = new ArrayList<>();
        Path www = Path.of("www");
        if (!Files.exists(www)) return result;

        try (Stream<Path> stream = Files.list(www)) {
            stream.filter(Files::isDirectory)
                .forEach(p -> result.add(p.getFileName().toString()));
        } catch (IOException ignored) {}

        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getConfig(Agent agent) {
        Object cfg = agent.get("config");
        if (cfg instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return new HashMap<>();
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }
}
