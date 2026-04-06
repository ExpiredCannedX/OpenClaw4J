package com.quashy.openclaw4j.tool.safety.audit;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一条追加式工具安全审计事件，覆盖策略判定、确认状态流转和最终执行结果。
 */
public record ToolAuditLogEntry(
        /**
         * 标识审计事件类型，例如 `policy_decision`、`confirmation_transition` 或 `execution_outcome`。
         */
        String eventType,
        /**
         * 绑定当前事件关联的待确认记录标识；非确认场景允许为空。
         */
        String confirmationId,
        /**
         * 绑定触发该事件的内部会话，便于按会话追踪高风险操作轨迹。
         */
        InternalConversationId conversationId,
        /**
         * 绑定触发该事件的内部用户，便于区分不同用户的操作意图与授权边界。
         */
        InternalUserId userId,
        /**
         * 标识当前事件对应的工具名，是审计聚合和排查的核心键之一。
         */
        String toolName,
        /**
         * 保存参数指纹，确保审计链路能在不回显完整参数的情况下关联同一请求。
         */
        String argumentsFingerprint,
        /**
         * 保存当次策略判定结果；无判定时允许为空。
         */
        String policyDecision,
        /**
         * 保存确认流状态；无确认语义时允许为空。
         */
        String confirmationStatus,
        /**
         * 保存最终执行结果，如 `success` 或结构化错误码；未执行时允许为空。
         */
        String executionOutcome,
        /**
         * 保存审计事件的稳定原因码，便于统计拒绝类别和失败类型。
         */
        String reasonCode,
        /**
         * 承载补充结构化诊断信息，避免把细节散落到自由文本日志里。
         */
        Map<String, Object> details,
        /**
         * 记录该审计事件的创建时间，供后续时间线回放和归档。
         */
        Instant createdAt
) {

    /**
     * 在审计记录创建时冻结可变负载并校验关键信息完整，避免写入半结构化事件。
     */
    public ToolAuditLogEntry {
        Assert.hasText(eventType, "eventType must not be blank");
        Assert.notNull(conversationId, "conversationId must not be null");
        Assert.notNull(userId, "userId must not be null");
        Assert.hasText(toolName, "toolName must not be blank");
        Assert.hasText(argumentsFingerprint, "argumentsFingerprint must not be blank");
        Assert.hasText(reasonCode, "reasonCode must not be blank");
        Assert.notNull(createdAt, "createdAt must not be null");
        details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }
}

