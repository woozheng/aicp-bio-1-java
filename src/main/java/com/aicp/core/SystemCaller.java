package com.aicp.core;

/**
 * SystemCaller — 跨插件调用接口
 *
 * SystemServer 实现它，Agent 转发它。
 * 插件通过 agent.call(envelop) 调它。
 */
public interface SystemCaller {
    Envelop call(Envelop envelop);
}
