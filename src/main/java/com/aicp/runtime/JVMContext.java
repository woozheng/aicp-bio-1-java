package com.aicp.runtime;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JVMContext — JVM 元能力
 *
 * runtime 层，挂到 Agent 上。
 * 让插件 / LLM 能"发现 JVM 里的一切"。
 */
public class JVMContext {

    private final ClassLoader classLoader;

    public JVMContext() {
        this.classLoader = Thread.currentThread().getContextClassLoader();
    }

    /** 反射一个类 */
    public Map<String, Object> introspect(String className) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            Map<String, Object> info = new HashMap<>();
            info.put("name", clazz.getName());

            List<Map<String, Object>> methods = new ArrayList<>();
            for (Method m : clazz.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;

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
            info.put("methods", methods);

            return info;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 读源码（从 classpath 找 .java） */
    public String readSource(String className) {
        String path = className.replace(".", "/") + ".java";
        try (java.io.InputStream in = classLoader.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
