package com.quashy.openclaw4j.tool.safety.confirmation;

/**
 * 表示高风险工具请求在持久化确认流中的生命周期状态。
 */
public enum ToolConfirmationStatus {

    /**
     * 表示请求已经被记录为待确认，但尚未收到匹配的显式确认消息。
     */
    PENDING,

    /**
     * 表示请求已经收到同会话同用户的显式确认，可被短路恢复执行一次。
     */
    CONFIRMED,

    /**
     * 表示请求已经被真实消费执行，后续不能再次拿来恢复。
     */
    CONSUMED,

    /**
     * 表示请求在过期窗口后仍未被合法确认，必须拒绝继续恢复执行。
     */
    EXPIRED
}

