package com.aicp.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局插件地址簿
 * 与 Python 版 core.py / TS 版 plugins.ts 完全对应
 *
 * 协议级（Tier 1）：插件按 receiver 在共享命名空间中可达。
 * 具体实现（Map）是 Tier 3 选择；也可以换成文件系统、数据库等。
 */
public final class Plugins {
    private Plugins() {}

    public static final Map<String, Plugin> INSTANCE = new ConcurrentHashMap<>();
}
