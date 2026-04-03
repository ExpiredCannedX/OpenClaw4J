package com.quashy.openclaw4j.channel.telegram;

/**
 * 定义 Telegram 渠道的最小出站端口，让 webhook 编排只依赖发送意图而不依赖具体 HTTP 实现。
 */
public interface TelegramOutboundClient {

    /**
     * 将最终文本回复发送到指定 Telegram 私聊会话；实现可以选择 HTTP、SDK 或其他 Bot API 适配方式。
     */
    void sendMessage(TelegramOutboundMessage outboundMessage);
}
