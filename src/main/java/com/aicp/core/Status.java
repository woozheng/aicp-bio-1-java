package com.aicp.core;

import java.util.Set;

/**
 * AICP 协议级失败状态（Tier 1）
 *
 * 这些常量由协议定义。插件实现可以读它们，但不应重新定义。
 * 与 Python 版 core.py / TS 版 status.ts 完全对应。
 *
 * 语义：
 *   空字符串       = 成功（无协议级错误）
 *   INVALID       = Envelop 违反约束（route 产生）
 *   MISSING       = receiver 无对应插件（route 产生）
 *   TIMEOUT       = 路由尝试未在时限内产生结果（route 产生）
 *   EXCEPTION     = 插件抛异常（插件产生）
 *   META_FAILED   = meta 契约无法满足，或拒绝（插件产生）
 *
 * 终止状态（消息不再被尝试）：
 *   DROPPED / ISOLATED / DORMANT
 */
public final class Status {
    private Status() {}

    public static final String OK = "";                 // 成功
    public static final String INVALID = "INVALID";     // Envelop 违反约束
    public static final String MISSING = "MISSING";     // receiver 无对应插件
    public static final String TIMEOUT = "TIMEOUT";     // 路由尝试超时
    public static final String EXCEPTION = "EXCEPTION"; // 插件抛异常
    public static final String META_FAILED = "META_FAILED"; // meta 契约无法满足

    // 终止状态：消息不再被尝试
    public static final String DROPPED = "DROPPED";
    public static final String ISOLATED = "ISOLATED";
    public static final String DORMANT = "DORMANT";

    private static final Set<String> TERMINAL_STATUSES = Set.of(
        DROPPED, ISOLATED, DORMANT
    );

    /** 判断 status 是否为终止状态。空字符串（成功）不是终止状态。 */
    public static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    /**
     * 协议辅助：把 Envelop 标记为失败。
     * 设置 status（协议路径），同时写入 payload["error"]（向后兼容）。
     */
    public static Envelop fail(Envelop envelop, String status, String message) {
        envelop.status = status;
        if (message != null && !message.isEmpty()) {
            envelop.payload.put("error", message);
        }
        return envelop;
    }
}
