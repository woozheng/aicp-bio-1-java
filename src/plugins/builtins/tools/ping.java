import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * builtins/tools/ping — 测试插件
 *
 * 与 TS 版 plugins/builtins/tools/ping.ts 对应。
 *
 * receiver 从"文件路径"推：src/plugins/builtins/tools/ping.java → builtins/tools/ping
 */
public class ping implements Plugin {

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        Map<String, Object> data = new HashMap<>();
        data.put("pong", true);
        data.put("echo", envelop.payload);
        data.put("received_at", java.time.Instant.now().toString());

        envelop.payload = new HashMap<>();
        envelop.payload.put("ok", true);
        envelop.payload.put("data", data);
        envelop.payload.put("message", "pong");

        return envelop;
    }
}
