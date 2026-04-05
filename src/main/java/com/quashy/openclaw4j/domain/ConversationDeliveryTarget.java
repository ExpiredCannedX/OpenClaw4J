package com.quashy.openclaw4j.domain;

import java.time.OffsetDateTime;

/**
 * 表示一个内部会话当前可用的主动回发目标，使异步能力能够脱离原始 webhook 请求重新定位到正确渠道会话。
 */
public record ConversationDeliveryTarget(
        /**
         * 标识当前绑定所归属的内部会话，是异步系统反查渠道目标时使用的唯一内部键。
         */
        InternalConversationId conversationId,
        /**
         * 标识当前目标所属渠道，例如 `telegram`，用于后续选择正确的渠道 sender。
         */
        String channel,
        /**
         * 保存渠道原生的会话目标标识，由具体 sender 解释为 chatId、threadId 等实际投递目标。
         */
        String externalConversationId,
        /**
         * 记录该绑定最近一次被刷新或确认有效的时间，用于排障和未来可能的过期策略。
         */
        OffsetDateTime updatedAt
) {
}
