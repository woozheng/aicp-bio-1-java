package com.aicp.core;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * route — 引擎路由
 * 与 TS 版 core/route.ts 对应。
 */
public class Route {

    private final ExecutorService asyncExecutor =
        Executors.newVirtualThreadPerTaskExecutor();

    public Envelop route(Envelop envelop, Agent agent) {
        return route(envelop, agent, 1200);
    }

    public Envelop route(Envelop envelop, Agent agent, long timeoutSeconds) {
        // INVALID
        if (envelop.receiver == null || envelop.receiver.isEmpty()) {
            envelop.status = Status.INVALID;
            envelop.payload.put("error", "Missing receiver");
            return envelop;
        }

        // DORMANT
        if (envelop.ttl <= 0) {
            envelop.status = Status.DORMANT;
            envelop.payload.put("error", "TTL expired");
            return envelop;
        }

        // 消耗 ttl
        envelop.ttl -= 1;

        // 查找插件
        Plugin plugin = Plugins.INSTANCE.get(envelop.receiver);
        if (plugin == null) {
            envelop.status = Status.MISSING;
            envelop.payload.put("error", "Plugin not found: " + envelop.receiver);
            return envelop;
        }

        // 同步执行（带超时）
        try {
            return runWithTimeout(() -> plugin.execute(envelop, agent), timeoutSeconds);
        } catch (Exception e) {
            envelop.status = Status.EXCEPTION;
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            envelop.payload.put("error", msg.length() > 200 ? msg.substring(0, 200) : msg);
            return envelop;
        }
    }

    @FunctionalInterface
    private interface PluginCall {
        Envelop call();
    }

    private Envelop runWithTimeout(PluginCall call, long timeoutSeconds) throws Exception {
        var future = asyncExecutor.submit(call::call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            future.cancel(true);
            throw new RuntimeException("Plugin timeout after " + timeoutSeconds + "s");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }
}
