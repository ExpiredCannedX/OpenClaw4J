package com.quashy.openclaw4j.channel.outbound;

import com.quashy.openclaw4j.channel.telegram.TelegramOutboundClient;
import com.quashy.openclaw4j.channel.telegram.TelegramOutboundMessage;
import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 提供 Telegram 私聊场景下的主动文本回发实现，把内部绑定目标转换为 Telegram `chat.id` 并调用既有出站客户端。
 */
@Component
@ConditionalOnProperty(prefix = "openclaw.telegram", name = "enabled", havingValue = "true")
public class TelegramProactiveConversationSender implements ChannelProactiveConversationSender {

    /**
     * 复用现有 Telegram 文本出站客户端，避免 reminder 回发链路重复实现 Bot API 调用细节。
     */
    private final TelegramOutboundClient telegramOutboundClient;

    /**
     * 通过显式注入既有 Telegram 出站端口固定职责边界，让主动回发只负责目标转换和错误语义收敛。
     */
    public TelegramProactiveConversationSender(TelegramOutboundClient telegramOutboundClient) {
        this.telegramOutboundClient = telegramOutboundClient;
    }

    /**
     * 返回 Telegram 渠道标识，供统一回发网关在解析绑定后路由到当前实现。
     */
    @Override
    public String channel() {
        return "telegram";
    }

    /**
     * 把外部会话目标解释为 Telegram `chat.id` 并发送纯文本，底层失败会被提升为稳定业务错误码。
     */
    @Override
    public void sendText(ConversationDeliveryTarget target, String text) {
        long chatId;
        try {
            chatId = Long.parseLong(target.externalConversationId());
        } catch (NumberFormatException exception) {
            throw new ConversationDeliveryFailureException(
                    "invalid_delivery_target",
                    "Telegram 回发目标不是合法的 chat.id。",
                    exception
            );
        }
        try {
            telegramOutboundClient.sendMessage(new TelegramOutboundMessage(chatId, text));
        } catch (RuntimeException exception) {
            throw new ConversationDeliveryFailureException(
                    "telegram_send_failed",
                    "Telegram 主动发送失败。",
                    exception
            );
        }
    }
}
