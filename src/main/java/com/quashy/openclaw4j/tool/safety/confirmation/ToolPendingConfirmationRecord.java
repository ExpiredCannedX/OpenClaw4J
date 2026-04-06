package com.quashy.openclaw4j.tool.safety.confirmation;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolRiskLevel;
import org.springframework.util.Assert;

import java.time.Instant;

/**
 * 表示一条持久化的高风险工具待确认记录，使显式确认和后续审计都只依赖服务端事实。
 */
public record ToolPendingConfirmationRecord(
        /**
         * 标识该待确认请求的稳定主键，供恢复执行和审计串联时唯一引用。
         */
        String confirmationId,
        /**
         * 绑定触发该请求的内部会话，防止跨会话确认串用。
         */
        InternalConversationId conversationId,
        /**
         * 绑定触发该请求的内部用户，防止不同用户借用同一会话中的待确认态。
         */
        InternalUserId userId,
        /**
         * 保存需要恢复执行的工具名，避免确认后再次依赖模型重规划目标工具。
         */
        String toolName,
        /**
         * 保存按稳定键序列化后的参数 JSON，用于恢复执行原始工具请求。
         */
        String normalizedArgumentsJson,
        /**
         * 保存参数指纹，供确认恢复和审计在不展开原始 JSON 的情况下进行一致性校验。
         */
        String argumentsFingerprint,
        /**
         * 保存该请求所属的风险等级，便于审计和确认提示稳定复用风险摘要。
         */
        ToolRiskLevel riskLevel,
        /**
         * 保存该请求的确认策略，保证恢复执行时仍能核验原始治理语义。
         */
        ToolConfirmationPolicy confirmationPolicy,
        /**
         * 保存执行前参数校验类型，使恢复执行仍会经过与首次请求一致的校验边界。
         */
        ToolArgumentValidatorType validatorType,
        /**
         * 标识当前记录在确认状态机中的位置。
         */
        ToolConfirmationStatus status,
        /**
         * 保存供用户或审计理解的风险摘要，避免提示和日志只能看到抽象枚举值。
         */
        String riskSummary,
        /**
         * 记录该请求首次进入待确认状态的时间，用于审计和排序。
         */
        Instant createdAt,
        /**
         * 记录该请求失效的绝对时间，使确认是否过期完全由服务端判定。
         */
        Instant expiresAt,
        /**
         * 记录该请求被显式确认的时间；未确认时为空。
         */
        Instant confirmedAt,
        /**
         * 记录该请求被真实消费执行的时间；未消费时为空。
         */
        Instant consumedAt
) {

    /**
     * 在记录创建时校验主键、身份、工具名和关键时间完整，避免状态机持久化不完整事实。
     */
    public ToolPendingConfirmationRecord {
        Assert.hasText(confirmationId, "confirmationId must not be blank");
        Assert.notNull(conversationId, "conversationId must not be null");
        Assert.notNull(userId, "userId must not be null");
        Assert.hasText(toolName, "toolName must not be blank");
        Assert.hasText(normalizedArgumentsJson, "normalizedArgumentsJson must not be blank");
        Assert.hasText(argumentsFingerprint, "argumentsFingerprint must not be blank");
        Assert.notNull(riskLevel, "riskLevel must not be null");
        Assert.notNull(confirmationPolicy, "confirmationPolicy must not be null");
        Assert.notNull(validatorType, "validatorType must not be null");
        Assert.notNull(status, "status must not be null");
        Assert.hasText(riskSummary, "riskSummary must not be blank");
        Assert.notNull(createdAt, "createdAt must not be null");
        Assert.notNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * 返回带新状态和时间戳的副本，使确认流更新保持显式且不可变。
     */
    public ToolPendingConfirmationRecord withStatus(
            ToolConfirmationStatus status,
            Instant confirmedAt,
            Instant consumedAt
    ) {
        return new ToolPendingConfirmationRecord(
                confirmationId,
                conversationId,
                userId,
                toolName,
                normalizedArgumentsJson,
                argumentsFingerprint,
                riskLevel,
                confirmationPolicy,
                validatorType,
                status,
                riskSummary,
                createdAt,
                expiresAt,
                confirmedAt,
                consumedAt
        );
    }
}

