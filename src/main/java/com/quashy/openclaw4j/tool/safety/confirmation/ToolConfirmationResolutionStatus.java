package com.quashy.openclaw4j.tool.safety.confirmation;

/**
 * 描述确认流在处理当前消息时的解析结果，使 Agent Core 能决定是否短路恢复待确认请求。
 */
public enum ToolConfirmationResolutionStatus {

    /**
     * 表示当前消息不是一次可消费的显式确认，主链路应继续常规规划流程。
     */
    NO_MATCH,

    /**
     * 表示当前消息成功命中一个待确认请求，主链路可以直接恢复执行存储的原始工具请求。
     */
    RESUMABLE,

    /**
     * 表示当前消息看起来是确认，但由于无待确认项、已过期或状态不匹配而被拒绝。
     */
    REJECTED
}

