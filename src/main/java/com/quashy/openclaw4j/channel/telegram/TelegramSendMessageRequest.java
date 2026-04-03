package com.quashy.openclaw4j.channel.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示 Telegram `sendMessage` API 的最小请求体，避免把 Bot API 字段名直接散落在业务编排代码中。
 */
public record TelegramSendMessageRequest(
        /**
         * Telegram Bot API 要求的目标 chat 标识，对应原始私聊会话的 `chat.id`。
         */
        @JsonProperty("chat_id")
        Long chatId,
        /**
         * Telegram Bot API 要求的文本正文；首版只发送最终一次性纯文本回复。
         */
        String text
) {
}
