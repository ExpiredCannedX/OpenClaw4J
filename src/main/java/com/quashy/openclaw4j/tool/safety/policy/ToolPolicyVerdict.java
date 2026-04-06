package com.quashy.openclaw4j.tool.safety.policy;

/**
 * 描述统一策略层对单次工具请求给出的最终放行结论。
 */
public enum ToolPolicyVerdict {

    /**
     * 表示请求已满足当前服务端安全边界，可以继续进入真实工具执行。
     */
    ALLOWED,

    /**
     * 表示请求违反策略，必须在不执行真实工具的前提下直接拒绝。
     */
    DENIED,

    /**
     * 表示请求本身可执行，但当前尚未命中显式确认态，因此只能进入待确认状态。
     */
    CONFIRMATION_REQUIRED
}

