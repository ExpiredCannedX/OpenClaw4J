package com.quashy.openclaw4j.channel.telegram;

/**
 * 表示 Telegram 渠道需要发送的最小文本消息，隔离统一回复模型与 Bot API 请求体细节。
 */
public record TelegramOutboundMessage(
        /**
         * 回复目标私聊会话的 Telegram `chat.id`，决定最终消息发送到哪个用户对话。
         */
        Long chatId,
        /**
         * 需要投递给 Telegram 用户的最终文本正文，当前直接来源于 `ReplyEnvelope.body`。
         */
        String text
) {
}
