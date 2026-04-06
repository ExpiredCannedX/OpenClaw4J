package com.quashy.openclaw4j.tool.safety.confirmation;

import com.quashy.openclaw4j.tool.model.ToolCallRequest;

/**
 * 表示确认流对当前消息的解析结果，使 Agent Core 能在短路恢复执行和常规规划之间做稳定选择。
 */
public record ToolConfirmationResolution(
        /**
         * 标识当前消息是否命中可恢复执行、普通无匹配或显式确认被拒绝。
         */
        ToolConfirmationResolutionStatus status,
        /**
         * 承载稳定原因码，便于最终回复和审计区分无匹配、过期和其他拒绝原因。
         */
        String reasonCode,
        /**
         * 解释当前解析结论，供最终回复阶段生成清晰提示。
         */
        String message,
        /**
         * 当命中待确认项时，指向需要恢复执行的持久化记录；否则允许为空。
         */
        ToolPendingConfirmationRecord pendingRecord,
        /**
         * 当命中待确认项时，承载恢复执行的原始工具请求；否则允许为空。
         */
        ToolCallRequest requestToResume
) {

    /**
     * 构造普通无匹配结果，使 Agent Core 能继续常规规划而不进入确认短路分支。
     */
    public static ToolConfirmationResolution noMatch() {
        return new ToolConfirmationResolution(
                ToolConfirmationResolutionStatus.NO_MATCH,
                "no_match",
                "Current message does not resolve any pending confirmation.",
                null,
                null
        );
    }

    /**
     * 构造一个可恢复执行的结果，把待确认记录和原始工具请求一并返回给上游。
     */
    public static ToolConfirmationResolution resumable(
            ToolPendingConfirmationRecord pendingRecord,
            ToolCallRequest requestToResume
    ) {
        return new ToolConfirmationResolution(
                ToolConfirmationResolutionStatus.RESUMABLE,
                "confirmation_resolved",
                "Pending tool request is confirmed and ready to resume.",
                pendingRecord,
                requestToResume
        );
    }

    /**
     * 构造一个被拒绝的确认结果，使 Agent Core 能在不恢复执行的前提下给出安全反馈。
     */
    public static ToolConfirmationResolution rejected(
            String reasonCode,
            String message,
            ToolPendingConfirmationRecord pendingRecord
    ) {
        return new ToolConfirmationResolution(
                ToolConfirmationResolutionStatus.REJECTED,
                reasonCode,
                message,
                pendingRecord,
                null
        );
    }
}

