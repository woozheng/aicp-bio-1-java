import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.AgentInspector;

import java.util.Map;   // ★ 加这行

/**
 * builtins/agents/capabilities — 能力发现
 *
 * receiver 从"文件路径"推：builtins/agents/capabilities
 *
 * 让 LLM "看到" Agent 上有什么能力。
 */
public class capabilities implements Plugin {

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "list";

        AgentInspector inspector = (AgentInspector) agent.get("inspector");
        if (inspector == null) {
            envelop.payload = Map.of("ok", false, "error", "Inspector not bound");
            return envelop;
        }

        if ("list".equals(action)) {
            envelop.payload = Map.of("ok", true, "capabilities", inspector.listCapabilities());
            return envelop;
        }

        if ("get".equals(action)) {
            String name = envelop.payload.get("name") != null
                ? envelop.payload.get("name").toString()
                : "";
            if (name.isEmpty()) {
                envelop.payload = Map.of("ok", false, "error", "name required");
                return envelop;
            }
            envelop.payload = Map.of("ok", true, "capability", inspector.introspect(name));
            return envelop;
        }

        envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
        return envelop;
    }
}
