package com.quashy.openclaw4j.tool.safety.policy;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示统一策略层对单次工具请求的结构化判定结果，使执行器能稳定映射为允许、拒绝或待确认。
 */
public record ToolPolicyDecision(
        /**
         * 标识当前策略判定的最终结论，是执行器是否继续真实调用的唯一依据。
         */
        ToolPolicyVerdict verdict,
        /**
         * 承载调用方可依赖的稳定原因码，便于最终回复和审计统一区分失败语义。
         */
        String reasonCode,
        /**
         * 解释当前判定的直接原因，帮助最终回复阶段生成可理解的安全提示。
         */
        String message,
        /**
         * 携带补充结构化细节，例如风险等级、敏感路径或待确认摘要。
         */
        Map<String, Object> details,
        /**
         * 在 `confirmation_required` 场景下绑定持久化待确认记录标识，供后续显式确认恢复执行。
         */
        String confirmationId
) {

    /**
     * 在判定结果创建时冻结细节载荷并校验核心字段完整，避免上游收到模糊或可变的策略结论。
     */
    public ToolPolicyDecision {
        Assert.notNull(verdict, "verdict must not be null");
        Assert.hasText(reasonCode, "reasonCode must not be blank");
        Assert.hasText(message, "message must not be blank");
        details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }

    /**
     * 构造一个可继续执行的放行结果，保持“已允许”场景的结构与拒绝场景一致。
     */
    public static ToolPolicyDecision allowed(Map<String, Object> details) {
        return new ToolPolicyDecision(
                ToolPolicyVerdict.ALLOWED,
                "allowed",
                "Tool request is allowed.",
                details,
                null
        );
    }

    /**
     * 构造一个策略拒绝结果，使执行器能够在不执行真实工具的前提下返回结构化错误。
     */
    public static ToolPolicyDecision denied(String reasonCode, String message, Map<String, Object> details) {
        return new ToolPolicyDecision(
                ToolPolicyVerdict.DENIED,
                reasonCode,
                message,
                details,
                null
        );
    }

    /**
     * 构造一个待确认结果，把持久化确认记录标识一并返回给上游用于后续恢复执行。
     */
    public static ToolPolicyDecision confirmationRequired(
            String reasonCode,
            String message,
            Map<String, Object> details,
            String confirmationId
    ) {
        return new ToolPolicyDecision(
                ToolPolicyVerdict.CONFIRMATION_REQUIRED,
                reasonCode,
                message,
                details,
                confirmationId
        );
    }
}

