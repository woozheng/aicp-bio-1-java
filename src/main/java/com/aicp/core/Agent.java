package com.aicp.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent — 能力容器（纯容器）
 *
 * core 层，不变。
 * 只提供 get / set / all / call，不知道"能力"是什么。
 */
public class Agent {
    private final Map<String, Object> attrs = new HashMap<>();

    public Agent() {}

    public Agent(Map<String, Object> kwargs) {
        if (kwargs != null) attrs.putAll(kwargs);
    }

    public Object get(String key) {
        return attrs.get(key);
    }

    public Object get(String key, Object defaultValue) {
        Object v = attrs.get(key);
        return v != null ? v : defaultValue;
    }

    public void set(String key, Object value) {
        attrs.put(key, value);
    }

    public Map<String, Object> all() {
        return attrs;
    }

    /** 跨插件调用入口 — 对齐 TS 的 agent.system.call(envelop) */
    public Envelop call(Envelop envelop) {
        Object sys = attrs.get("system");
        if (sys instanceof SystemCaller caller) {
            return caller.call(envelop);
        }
        return null;
    }
}
