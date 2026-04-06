package com.quashy.openclaw4j.tool.safety.model;

/**
 * 描述服务端对工具副作用边界的稳定风险分级，供统一策略层和审计日志共享同一语义。
 */
public enum ToolRiskLevel {

    /**
     * 表示工具只读取已有事实而不产生持久化或外部副作用，可默认直接执行。
     */
    READ_ONLY,

    /**
     * 表示工具会改变本地状态或持久化数据，但破坏面相对受控。
     */
    STATE_CHANGING,

    /**
     * 表示工具可能删除、覆盖、移动或以其他方式造成较难恢复的破坏性后果。
     */
    DESTRUCTIVE,

    /**
     * 表示工具会把请求发送到外部网络边界之外，带来额外的数据泄露与副作用风险。
     */
    EXTERNAL_NETWORK
}

