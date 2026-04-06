package com.quashy.openclaw4j.reminder.model;

import com.quashy.openclaw4j.conversation.InternalConversationId;

import java.time.OffsetDateTime;

/**
 * 承载创建一次性 reminder 所需的最小事实，避免仓储和应用服务之间传递松散原始参数。
 */
public record ReminderCreateCommand(
        /**
         * 标识 reminder 最终要回到的内部会话，是异步履约阶段解析回发目标的唯一内部主键。
         */
        InternalConversationId conversationId,
        /**
         * 保存创建时所在渠道标识，便于后续统计、观测和兼容不同渠道能力差异。
         */
        String channel,
        /**
         * 表示 reminder 计划触发的绝对时间点，必须在进入该命令前已经是带时区的未来时间。
         */
        OffsetDateTime scheduledAt,
        /**
         * 保存最终需要主动回发给用户的纯文本提醒正文。
         */
        String reminderText
) {
}