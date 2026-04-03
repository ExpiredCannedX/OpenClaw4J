package com.quashy.openclaw4j.channel.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示当前 change 需要处理的最小 Telegram webhook update 结构，只保留私聊文本接入所需字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        /**
         * Telegram 为每个 incoming update 分配的全局唯一标识，用于作为统一幂等键来源。
         */
        @JsonProperty("update_id")
        Long updateId,
        /**
         * Telegram 文本消息载荷；首版只处理该字段承载的私聊文本消息。
         */
        TelegramMessage message
) {

    /**
     * 表示 Telegram update 中承载私聊消息的最小消息结构，只覆盖文本闭环所需字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramMessage(
            /**
             * Telegram chat 内部的消息编号；当前仅用于保留最小协议结构，不参与统一幂等键计算。
             */
            @JsonProperty("message_id")
            Long messageId,
            /**
             * 用户在 Telegram 私聊里发送的文本正文，是当前首版唯一支持的内容类型。
             */
            String text,
            /**
             * 消息所属 chat 的最小视图，用于提取回复所需的 `chat.id` 和私聊类型。
             */
            TelegramChat chat,
            /**
             * 消息发送人的最小视图，用于提取统一身份映射所需的外部用户标识。
             */
            TelegramUser from
    ) {
    }

    /**
     * 表示 Telegram chat 的最小结构，用于区分私聊与其他会话类型，并保留回复目标 chat 标识。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChat(
            /**
             * Telegram chat 的原生标识，首版直接作为统一外部会话 ID 和回复目标。
             */
            Long id,
            /**
             * Telegram chat 类型；当前仅接受 `private`。
             */
            String type
    ) {
    }

    /**
     * 表示 Telegram 用户的最小结构，只保留统一身份映射所需的用户 ID。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramUser(
            /**
             * Telegram 用户原生标识，进入统一单聊主链路前会被转换为字符串形式。
             */
            Long id
    ) {
    }
}
