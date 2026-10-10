package com.aicp.runtime;

import com.aicp.core.Agent;

import java.lang.reflect.Method;
import java.util.*;

/**
 * AgentInspector — Agent 能力检查器（元能力）
 *
 * runtime 层，挂到 Agent 上。
 * 让插件 / LLM 能"发现 Agent 上的能力"。
 *
 * ★ 过滤"原生类型"（java.* / javax.* / Map / List / String）
 */
public class AgentInspector {

    private final Agent agent;

    public AgentInspector(Agent agent) {
        this.agent = agent;
    }

    /** 列所有能力 */
    public Map<String, Object> listCapabilities() {
        Map<String, Object> caps = new HashMap<>();
        for (Map.Entry<String, Object> e : agent.all().entrySet()) {
            Object value = e.getValue();
            // ★ 过滤"原生类型"
            if (isNative(value)) continue;
            caps.put(e.getKey(), introspectValue(value));
        }
        return caps;
    }

    /** 查单个能力 */
    public Map<String, Object> introspect(String key) {
        Object value = agent.get(key);
        if (value == null) return Map.of("error", "Not found: " + key);
        Map<String, Object> result = introspectValue(value);
        result.put("name", key);
        return result;
    }

    // ============================================================
    // 过滤
    // ============================================================

    private boolean isNative(Object value) {
        if (value == null) return true;
        Class<?> clazz = value.getClass();
        String pkg = clazz.getPackageName();

        // Java 标准库
        if (pkg.startsWith("java.") || pkg.startsWith("javax.")
            || pkg.startsWith("sun.") || pkg.startsWith("jdk.")) {
            return true;
        }

        // 常见"简单类型"
        return clazz == String.class
            || Number.class.isAssignableFrom(clazz)
            || Boolean.class == clazz
            || Map.class.isAssignableFrom(clazz)
            || Collection.class.isAssignableFrom(clazz);
    }

    // ============================================================
    // 反射
    // ============================================================

    private Map<String, Object> introspectValue(Object value) {
        Map<String, Object> info = new HashMap<>();
        if (value == null) {
            info.put("type", "null");
            return info;
        }

        info.put("type", value.getClass().getName());

        List<Map<String, Object>> methods = new ArrayList<>();
        try {
            for (Method m : value.getClass().getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                if (m.getName().startsWith("get") || m.getName().startsWith("set")) continue;
                if (m.getName().equals("wait") || m.getName().equals("notify")
                    || m.getName().equals("notifyAll")) continue;

                Map<String, Object> method = new HashMap<>();
                method.put("name", m.getName());
                method.put("returns", m.getReturnType().getSimpleName());

                List<String> params = new ArrayList<>();
                for (Class<?> p : m.getParameterTypes()) {
                    params.add(p.getSimpleName());
                }
                method.put("params", params);
                methods.add(method);
            }
        } catch (Exception ignored) {}
        info.put("methods", methods);

        return info;
    }
}
