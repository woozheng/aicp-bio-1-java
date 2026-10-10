package com.aicp.runtime;

import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;
import com.aicp.core.Route;
import com.aicp.core.SystemCaller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SystemServer — 系统调用入口
 *
 * 与 TS 版 runtime/system.ts 对应。
 *
 * 职责：
 * 1. 提供 agent.call(envelop) 作为"路由入口"
 * 2. 负责加载和持有 Agent 实例
 * 3. 统一处理插件的调用
 * 4. 可视化旁路：call 前后塞事件到队列，异步推 WebSocket（简化版）
 */
public class SystemServer implements SystemCaller {

    // ============================================================
    // 字段
    // ============================================================

    private final Route route;
    private final Agent agent;

    private final Map<String, Object> config;
    private final String dataDir;
    private final String baseUrl;

    // ---------- 可视化配置 ----------
    private boolean vizEnabled = false;
    private String vizChannel = "pa_visualizer";
    private int vizQueueMax = 1000;
    private final BlockingQueue<VizItem> vizQueue = new LinkedBlockingQueue<>();
    private volatile boolean vizRunning = false;
    private Thread vizThread;

    // ============================================================
    // 内部类型：可视化事件
    // ============================================================

    private record VizItem(String direction, Envelop envelop, Envelop response) {}

    // ============================================================
    // 构造
    // ============================================================

    @SuppressWarnings("unchecked")
    public SystemServer(Map<String, Object> config, String dataDir, String baseUrl) {
        this.config = config != null ? config : new HashMap<>();
        this.dataDir = dataDir != null ? dataDir : "data";
        this.baseUrl = baseUrl != null ? baseUrl : "http://127.0.0.1:9000";

        this.route = new Route();

        // ---------- 读可视化配置 ----------
        Object vizObj = this.config.get("visualization");
        int queueSize = 1000;
        if (vizObj instanceof Map<?, ?> viz) {
            Object enabled = viz.get("enabled");
            this.vizEnabled = Boolean.TRUE.equals(enabled);
            Object ch = viz.get("channel");
            if (ch != null) this.vizChannel = ch.toString();
            Object qs = viz.get("queue_size");
            if (qs instanceof Number n) queueSize = n.intValue();
        } else if (Boolean.TRUE.equals(vizObj)) {
            this.vizEnabled = true;
        }
        this.vizQueueMax = queueSize;

        // ---------- 构造 Agent ----------
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("config", this.config);
        attrs.put("data_dir", this.dataDir);
        attrs.put("base_url", this.baseUrl);
        attrs.put("log", System.out);   // Java 里用 System.out 代表 console
        attrs.put("system", this);      // agent.system 指向自己
        attrs.put("llm", null);

        this.agent = new Agent(attrs);
        this.agent.set("inspector", new AgentInspector(this.agent));
        this.agent.set("jvm", new JVMContext());
    }

    // ============================================================
    // 跨插件调用入口
    // ============================================================

    @Override
    public Envelop call(Envelop envelop) {
        // 注入 session_id 到 meta（如果没传）
        if (envelop.meta.get("session_id") == null && agent.get("session_id") != null) {
            envelop.meta.put("session_id", agent.get("session_id"));
        }

        // 注入 token（如果没传）
        Object tokens = this.config.get("tokens");
        if (envelop.meta.get("token") == null && tokens instanceof List<?> list && !list.isEmpty()) {
            envelop.meta.put("token", list.get(0));
        }

        // 可视化推送（out）
        if (vizEnabled) {
            vizPush("out", envelop, null);
        }

        // 调 route
        Envelop response = route.route(envelop, agent);

        // 可视化推送（in）
        if (vizEnabled) {
            vizPush("in", envelop, response);
        }

        return response;
    }

    // ============================================================
    // 插件管理
    // ============================================================

    public void register(String receiver, Plugin plugin) {
        Plugins.INSTANCE.put(receiver, plugin);
    }

    public void unregister(String receiver) {
        Plugins.INSTANCE.remove(receiver);
    }

    public List<String> listPlugins() {
        return new ArrayList<>(Plugins.INSTANCE.keySet());
    }

    // ============================================================
    // 设置 LLM 能力
    // ============================================================

    public void setLLM(Object llm) {
        agent.set("llm", llm);
    }

    // ============================================================
    // 可视化
    // ============================================================

    /**
     * 启动可视化 worker（如果开启）
     */
    public void startVisualizer() {
        if (!vizEnabled || vizRunning) return;
        vizRunning = true;
        vizThread = new Thread(this::vizLoop, "Visualizer");
        vizThread.setDaemon(true);
        vizThread.start();
        System.out.println("[Visualizer] Started, channel=" + vizChannel);
    }

    public void stopVisualizer() {
        vizRunning = false;
        if (vizThread != null) vizThread.interrupt();
    }

    private void vizPush(String direction, Envelop envelop, Envelop response) {
        if (vizQueue.size() >= vizQueueMax) {
            return;   // 队列满，丢弃（不影响主流程）
        }
        vizQueue.offer(new VizItem(direction, envelop, response));
    }

    private void vizLoop() {
        while (vizRunning) {
            try {
                VizItem item = vizQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (item == null) continue;
                vizPushEvent(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
                // 推送失败不影响主流程
            }
        }
    }

    private void vizPushEvent(VizItem item) {
        // 直接走 route，避免走 system.call 造成递归
        Envelop vizEnvelop = new Envelop("os/_visualizer", "os/_websocket");

        Map<String, Object> data = new HashMap<>();
        data.put("type", "envelop");
        data.put("direction", item.direction);
        data.put("sender", item.envelop.sender);
        data.put("receiver", item.envelop.receiver);
        data.put("intent", item.envelop.intent);
        data.put("trace_id", item.envelop.traceId);
        data.put("timestamp", System.currentTimeMillis() / 1000.0);
        data.put("has_response", item.response != null);

        vizEnvelop.payload.put("action", "push");
        vizEnvelop.payload.put("channel_id", vizChannel);
        vizEnvelop.payload.put("data", data);

        try {
            route.route(vizEnvelop, agent);
        } catch (Exception ignored) {
            // ignore
        }
    }

    // ============================================================
    // getter
    // ============================================================

    public Agent getAgent() {
        return agent;
    }

    public Route getRoute() {
        return route;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
