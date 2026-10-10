import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.runtime.LLM;

import java.util.List;
import java.util.Map;

/**
 * builtins/tools/llm_test — LLM 测试插件
 *
 * 与 TS 版对应。
 *
 * receiver 从"文件路径"推：src/plugins/builtins/tools/llm_test.java → builtins/tools/llm_test
 */
public class llm_test implements Plugin {

    @Override
    public Envelop execute(Envelop envelop, Agent agent) {
        Object llmObj = agent.get("llm");
        if (!(llmObj instanceof LLM llm)) {
            envelop.payload = Map.of("ok", false, "error", "LLM not initialized");
            return envelop;
        }

        String action = envelop.payload.get("action") != null
            ? envelop.payload.get("action").toString()
            : "chat";

        String prompt = envelop.payload.get("prompt") != null
            ? envelop.payload.get("prompt").toString()
            : "你好，请用一句话介绍自己";

        if ("chat".equals(action)) {
            String result = llm.chat(List.of(
                Map.of("role", "user", "content", prompt)
            ));
            envelop.payload = Map.of("ok", true, "data", result);
            return envelop;
        }

        if ("chat_json".equals(action)) {
            Map<String, Object> result = llm.chatJson(List.of(
                Map.of("role", "user", "content", prompt)
            ));
            envelop.payload = Map.of("ok", true, "data", result);
            return envelop;
        }

        if ("chat_stream".equals(action)) {
            StringBuilder sb = new StringBuilder();
            llm.chatStream(
                List.of(Map.of("role", "user", "content", prompt)),
                (delta, done, error) -> {
                    if (delta != null && !delta.isEmpty()) sb.append(delta);
                    if (error != null) sb.append("\n[ERROR] ").append(error);
                }
            );
            envelop.payload = Map.of("ok", true, "data", sb.toString());
            return envelop;
        }

        envelop.payload = Map.of("ok", false, "error", "Unknown action: " + action);
        return envelop;
    }
}
