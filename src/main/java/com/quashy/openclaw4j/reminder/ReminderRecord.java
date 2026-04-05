package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.domain.InternalConversationId;

import java.time.OffsetDateTime;

/**
 * 表示 reminder SQLite 事实源中的一条完整提醒记录，使调度器、工具服务和测试都基于同一结构化视图工作。
 */
public record ReminderRecord(
        /**
         * 标识 reminder 的稳定唯一主键，供工具返回、状态流转和调度观测统一关联同一任务。
         */
        String reminderId,
        /**
         * 标识该提醒归属的内部会话，用于异步履约阶段反查当前渠道回发目标。
         */
        InternalConversationId conversationId,
        /**
         * 记录创建该提醒时的渠道标识，帮助观测与后续多渠道能力扩展保持最小上下文。
         */
        String channel,
        /**
         * 记录 reminder 计划首次触发的绝对时间点，始终保留原始调度目标而不随重试变化。
         */
        OffsetDateTime scheduledAt,
        /**
         * 保存最终需要回发给用户的纯文本内容，是 reminder 任务真正履约的业务载荷。
         */
        String reminderText,
        /**
         * 表示当前 reminder 的调度状态，用于区分待扫描、执行中、已完成和终态失败。
         */
        ReminderStatus status,
        /**
         * 记录当前 reminder 已经历的失败重试次数，用于与运行时预算比较决定是否继续重排。
         */
        int attemptCount,
        /**
         * 表示当前任务下一次可再次被扫描的时间点；首次创建时与 `scheduledAt` 保持一致。
         */
        OffsetDateTime nextAttemptAt,
        /**
         * 保存最近一次失败的结构化错误码，便于调试和观测区分缺失绑定、渠道失败等不同问题。
         */
        String lastErrorCode,
        /**
         * 记录 reminder 首次写入事实源的时间，帮助后续排查任务生命周期。
         */
        OffsetDateTime createdAt,
        /**
         * 记录 reminder 最近一次状态流转的时间，帮助调度器和运维判断任务新鲜度。
         */
        OffsetDateTime updatedAt
) {
}
