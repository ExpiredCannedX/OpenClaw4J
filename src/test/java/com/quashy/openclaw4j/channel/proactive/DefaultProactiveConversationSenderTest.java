package com.quashy.openclaw4j.channel.proactive;

import com.quashy.openclaw4j.channel.telegram.TelegramOutboundClient;
import com.quashy.openclaw4j.channel.telegram.TelegramOutboundMessage;
import com.quashy.openclaw4j.conversation.ConversationDeliveryTarget;
import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryConversationDeliveryTargetRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 验证平台无关的主动回发抽象会先解析内部会话绑定，再把文本交给具体渠道 sender，并对失败返回明确错误语义。
 */
class DefaultProactiveConversationSenderTest {

    /**
     * 已绑定到 Telegram 私聊的内部会话必须能通过统一抽象成功发送纯文本，而不是让 Scheduler 直接依赖 Telegram API 细节。
     */
    @Test
    void shouldSendPlainTextThroughTelegramSenderForResolvedConversation() {
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        InMemoryConversationDeliveryTargetRepository repository = new InMemoryConversationDeliveryTargetRepository();
        InternalConversationId conversationId = new InternalConversationId("conversation-1");
        repository.save(new ConversationDeliveryTarget(
                conversationId,
                "telegram",
                "2001",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00")
        ));
        DefaultProactiveConversationSender sender = new DefaultProactiveConversationSender(
                repository,
                List.of(new TelegramProactiveConversationSender(telegramOutboundClient))
        );

        sender.sendText(conversationId, "提醒内容");

        verify(telegramOutboundClient).sendMessage(new TelegramOutboundMessage(2001L, "提醒内容"));
    }

    /**
     * 缺失绑定时必须向上返回显式的 `delivery_target_missing` 错误码，避免调度器把“没有目标”误判为成功投递。
     */
    @Test
    void shouldFailWithExplicitErrorCodeWhenDeliveryTargetIsMissing() {
        DefaultProactiveConversationSender sender = new DefaultProactiveConversationSender(
                new InMemoryConversationDeliveryTargetRepository(),
                List.of()
        );

        assertThatThrownBy(() -> sender.sendText(new InternalConversationId("conversation-missing"), "提醒内容"))
                .isInstanceOfSatisfying(ConversationDeliveryFailureException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("delivery_target_missing");
                });
    }

    /**
     * Telegram 主动发送失败时必须向上返回稳定错误码，保证调度器能按失败预算决定重试或终态失败。
     */
    @Test
    void shouldSurfaceTelegramSendFailureWithStableErrorCode() {
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        doThrow(new IllegalStateException("send failed"))
                .when(telegramOutboundClient)
                .sendMessage(new TelegramOutboundMessage(2001L, "提醒内容"));
        InMemoryConversationDeliveryTargetRepository repository = new InMemoryConversationDeliveryTargetRepository();
        repository.save(new ConversationDeliveryTarget(
                new InternalConversationId("conversation-2"),
                "telegram",
                "2001",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00")
        ));
        DefaultProactiveConversationSender sender = new DefaultProactiveConversationSender(
                repository,
                List.of(new TelegramProactiveConversationSender(telegramOutboundClient))
        );

        assertThatThrownBy(() -> sender.sendText(new InternalConversationId("conversation-2"), "提醒内容"))
                .isInstanceOfSatisfying(ConversationDeliveryFailureException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("telegram_send_failed");
                });
    }
}