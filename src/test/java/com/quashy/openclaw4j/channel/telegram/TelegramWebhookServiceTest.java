package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.agent.api.AgentFacade;
import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.channel.dm.DirectMessageService;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.store.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.store.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.store.memory.InMemoryProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Telegram adapter 只做协议翻译与回复投递，并通过统一 ingress 服务接入现有单聊核心。
 */
class TelegramWebhookServiceTest {

    /**
     * 不受支持的 update 必须被安全忽略，避免群聊、非文本或其他 Telegram 事件污染单聊核心。
     */
    @Test
    void shouldIgnoreUnsupportedUpdateWithoutInvokingCoreOrSendingReply() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        TelegramWebhookService service = new TelegramWebhookService(
                new DirectMessageService(
                        new InMemoryIdentityMappingRepository(),
                        new InMemoryActiveConversationRepository(),
                        new InMemoryProcessedMessageRepository(),
                        agentFacade
                ),
                telegramOutboundClient
        );

        service.handle(new TelegramUpdate(
                1001L,
                new TelegramUpdate.TelegramMessage(
                        3001L,
                        null,
                        new TelegramUpdate.TelegramChat(2001L, "group"),
                        new TelegramUpdate.TelegramUser(4001L)
                )
        ));

        verify(agentFacade, never()).reply(any());
        verify(telegramOutboundClient, never()).sendMessage(any());
    }

    /**
     * 合法的 Telegram 私聊文本消息必须映射成统一 ingress 命令，并把最终回复发送回原始 `chat.id`。
     */
    @Test
    void shouldMapTelegramPrivateTextUpdateToUnifiedIngressAndSendReplyToSourceChat() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("已收到", List.of()));
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        TelegramWebhookService service = new TelegramWebhookService(
                new DirectMessageService(
                        new InMemoryIdentityMappingRepository(),
                        new InMemoryActiveConversationRepository(),
                        new InMemoryProcessedMessageRepository(),
                        agentFacade
                ),
                telegramOutboundClient
        );

        service.handle(new TelegramUpdate(
                1001L,
                new TelegramUpdate.TelegramMessage(
                        3001L,
                        "你好",
                        new TelegramUpdate.TelegramChat(2001L, "private"),
                        new TelegramUpdate.TelegramUser(4001L)
                )
        ));

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentFacade).reply(requestCaptor.capture());
        AgentRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.message().channel()).isEqualTo("telegram");
        assertThat(capturedRequest.message().externalUserId()).isEqualTo("4001");
        assertThat(capturedRequest.message().externalConversationId()).isEqualTo("2001");
        assertThat(capturedRequest.message().externalMessageId()).isEqualTo("1001");
        assertThat(capturedRequest.message().body()).isEqualTo("你好");
        verify(telegramOutboundClient).sendMessage(new TelegramOutboundMessage(2001L, "已收到"));
    }

    /**
     * 相同 `update_id` 的重复投递必须既避免重复触发 Agent 计算，也避免把同一条回复重复发送给 Telegram 用户。
     */
    @Test
    void shouldReuseUpdateIdAsIdempotencyKeyAcrossDuplicateWebhookDeliveries() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("已收到", List.of()));
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        TelegramWebhookService service = new TelegramWebhookService(
                new DirectMessageService(
                        new InMemoryIdentityMappingRepository(),
                        new InMemoryActiveConversationRepository(),
                        new InMemoryProcessedMessageRepository(),
                        agentFacade
                ),
                telegramOutboundClient
        );
        TelegramUpdate update = new TelegramUpdate(
                1001L,
                new TelegramUpdate.TelegramMessage(
                        3001L,
                        "你好",
                        new TelegramUpdate.TelegramChat(2001L, "private"),
                        new TelegramUpdate.TelegramUser(4001L)
                )
        );

        service.handle(update);
        service.handle(update);

        verify(agentFacade, times(1)).reply(any());
        verify(telegramOutboundClient, times(1)).sendMessage(new TelegramOutboundMessage(2001L, "已收到"));
    }
}
