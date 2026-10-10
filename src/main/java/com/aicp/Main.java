package com.aicp;

import com.aicp.core.Agent;
import com.aicp.core.Envelop;
import com.aicp.core.Plugin;
import com.aicp.core.Plugins;
import com.aicp.runtime.Config;
import com.aicp.runtime.LLM;
import com.aicp.runtime.Logger;
import com.aicp.runtime.PluginLoader;
import com.aicp.runtime.SystemServer;

import java.util.Map;

/**
 * AICP 本地引擎 · 极简启动器（Java 版）
 *
 * 对应 Python 版 runtime/__main__.py / TS 版 src/main.ts
 *
 * 流程：
 *   1. 读 config
 *   2. 初始化 LLM（可选）
 *   3. 初始化 SystemServer + Agent
 *   4. 启动可视化（可选）
 *   5. 加载插件
 *   5.5 恢复 os/_cron 持久化任务
 *   6. 启动 FileWatcher（热重载）
 *   7. 启动 gateway
 *   7.5 启动 file_receiver（port + 2）
 *   7.6 启动 websocket（port + 1）
 *   8. 主循环（等 Ctrl+C）
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // ============================================================
        // 1. 读 config
        // ============================================================
        Logger.install();

        Config config = Config.load();

        String host = config.getString("host", "0.0.0.0");
        int port = config.getInt("port", 9000);
        String baseUrl = "http://127.0.0.1:" + port;

        System.out.println("=".repeat(50));
        System.out.println("  AICP Engine (Java) · http://" + host + ":" + port);
        System.out.println("=".repeat(50));

        // ============================================================
        // 2. 初始化 LLM（可选）
        // ============================================================
        LLM llm = null;
        try {
            Map<String, Object> modelsCfg = config.getMap("models");
            Object providersObj = modelsCfg.get("providers");
            if (providersObj instanceof Map<?, ?> p && !p.isEmpty()) {
                llm = new LLM(modelsCfg);
                System.out.println("LLM: initialized");
            } else {
                System.out.println("LLM: no providers configured, skip");
            }
        } catch (Exception e) {
            System.out.println("LLM init failed: " + e.getMessage());
        }

        // ============================================================
        // 3. 初始化 SystemServer + Agent
        // ============================================================
        SystemServer system = new SystemServer(
            config.all(),
            config.getString("data_dir", "data"),
            baseUrl
        );

        Agent agent = system.getAgent();

        // 把 LLM 挂到 agent 上
        if (llm != null) {
            agent.set("llm", llm);
            system.setLLM(llm);   // 同步到 SystemServer
        }

        // ============================================================
        // 4. 启动可视化（可选）
        // ============================================================
        try {
            system.startVisualizer();
        } catch (Exception e) {
            System.err.println("Visualizer start failed: " + e.getMessage());
        }

        // ============================================================
        // 5. 加载插件
        // ============================================================
        System.out.println("Loading plugins...");

        String pluginsDir = config.getString("plugins_dir", "src/plugins");
        PluginLoader loader = new PluginLoader(pluginsDir);
        int count = loader.loadAll();

        System.out.println("Plugins: " + count + " loaded");
        for (String name : system.listPlugins()) {
            System.out.println("  /api/" + name);
        }

        // ============================================================
        // 5.5 恢复 os/_cron 持久化任务
        // ============================================================
        try {
            Plugin cron = Plugins.INSTANCE.get("os/_cron");
            if (cron != null) {
                Envelop cronEnv = new Envelop("__main__", "os/_cron");
                cronEnv.payload.put("action", "restore");
                Envelop cronResult = system.call(cronEnv);
                System.out.println("os/_cron: restored " + cronResult.payload.get("restored") + " tasks");
            } else {
                System.out.println("os/_cron: not found, skip");
            }
        } catch (Exception e) {
            System.err.println("os/_cron restore failed: " + e.getMessage());
        }

        // ============================================================
        // 6. 启动 FileWatcher（热重载）
        // ============================================================
        com.aicp.runtime.FileWatcher watcher = null;
        try {
            watcher = new com.aicp.runtime.FileWatcher(pluginsDir, loader);
            watcher.start();
        } catch (Exception e) {
            System.err.println("FileWatcher start failed: " + e.getMessage());
        }

        // ============================================================
        // 7. 启动 gateway
        // ============================================================
        Plugin gateway = Plugins.INSTANCE.get("os/_gateway");
        if (gateway == null) {
            System.err.println("os/_gateway not found!");
            return;
        }

        System.out.println("Starting os/_gateway...");

        Envelop startEnv = new Envelop("__main__", "os/_gateway");
        startEnv.intent = "START";
        startEnv.payload.put("action", "START");
        startEnv.payload.put("port", port);
        startEnv.payload.put("host", host);

        Envelop result;
        try {
            result = system.call(startEnv);
        } catch (Exception e) {
            System.err.println("Gateway start failed: " + e.getMessage());
            return;
        }

        Object status = result.payload.get("status");
        if ("listening".equals(status)) {
            System.out.println("Gateway listening on http://" + host + ":" + port);
        } else {
            System.err.println("Gateway start failed: " + result.payload);
            return;
        }

        // ============================================================
        // 7.5 启动 os/_file_receiver（port + 2）
        // ============================================================
        try {
            Plugin fileRecv = Plugins.INSTANCE.get("os/_file_receiver");
            if (fileRecv != null) {
                Envelop frEnv = new Envelop("__main__", "os/_file_receiver");
                frEnv.payload.put("action", "START");
                frEnv.payload.put("port", port + 2);
                frEnv.payload.put("host", host);
                Envelop frResult = system.call(frEnv);
                Object frStatus = frResult.payload.get("status");
                if ("listening".equals(frStatus)) {
                    System.out.println("FileReceiver listening on http://" + host + ":" + (port + 2));
                } else {
                    System.err.println("FileReceiver start failed: " + frResult.payload);
                }
            } else {
                System.out.println("os/_file_receiver not found, skip");
            }
        } catch (Exception e) {
            System.err.println("FileReceiver start failed: " + e.getMessage());
        }

        // ============================================================
        // 7.6 启动 os/_websocket（port + 1）
        // ============================================================
        try {
            Plugin ws = Plugins.INSTANCE.get("os/_websocket");
            if (ws != null) {
                Envelop wsEnv = new Envelop("__main__", "os/_websocket");
                wsEnv.payload.put("action", "START");
                wsEnv.payload.put("port", port + 1);
                wsEnv.payload.put("host", host);
                Envelop wsResult = system.call(wsEnv);
                Object wsStatus = wsResult.payload.get("status");
                if ("listening".equals(wsStatus)) {
                    System.out.println("WebSocket listening on ws://" + host + ":" + (port + 1) + "/ws");
                } else {
                    System.err.println("WebSocket start failed: " + wsResult.payload);
                }
            } else {
                System.out.println("os/_websocket not found, skip");
            }
        } catch (Exception e) {
            System.err.println("WebSocket start failed: " + e.getMessage());
        }

        // ============================================================
        // 8. 主循环
        // ============================================================
        System.out.println();
        System.out.println("AICP Java ready. Press Ctrl+C to stop.");

        final SystemServer sysRef = system;
        final Plugin gwRef = gateway;
        final com.aicp.runtime.FileWatcher watcherRef = watcher;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println("Stopping services...");

            // 停可视化
            try {
                sysRef.stopVisualizer();
            } catch (Exception ignored) {}

            // 停 FileWatcher
            if (watcherRef != null) {
                try {
                    watcherRef.stop();
                } catch (Exception ignored) {}
            }

            // 停 gateway
            try {
                Envelop stopEnv = new Envelop("__main__", "os/_gateway");
                stopEnv.intent = "STOP";
                stopEnv.payload.put("action", "STOP");
                gwRef.execute(stopEnv, sysRef.getAgent());
            } catch (Exception e) {
                System.err.println("Gateway stop failed: " + e.getMessage());
            }

            System.out.println("Goodbye");
        }));

        Thread.currentThread().join();
    }
}
