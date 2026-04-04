package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.agent.api.AgentFacade;
import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.channel.dm.DirectMessageService;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.store.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.store.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.store.memory.InMemoryProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * Telegram 出站失败时必须发出带同一 `runId` 的失败事件，避免只剩裸日志而无法和本轮处理链路关联。
     */
    @Test
    void shouldEmitOutboundFailureObservationWhenTelegramSendFails() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("已收到", List.of()));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.TIMELINE);
        TelegramOutboundClient telegramOutboundClient = mock(TelegramOutboundClient.class);
        TelegramWebhookService service = new TelegramWebhookService(
                new DirectMessageService(
                        new InMemoryIdentityMappingRepository(),
                        new InMemoryActiveConversationRepository(),
                        new InMemoryProcessedMessageRepository(),
                        agentFacade,
                        publisher
                ),
                telegramOutboundClient,
                publisher
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
        org.mockito.Mockito.doThrow(new IllegalStateException("send failed"))
                .when(telegramOutboundClient)
                .sendMessage(any());

        service.handle(update);

        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("telegram.update.received", "telegram.outbound.send_started", "telegram.outbound.failed");
        String outboundRunId = publisher.events.stream()
                .filter(event -> event.eventType().startsWith("telegram.outbound"))
                .findFirst()
                .orElseThrow()
                .traceContext()
                .runId();
        assertThat(publisher.events.stream()
                .filter(event -> event.eventType().startsWith("telegram.outbound"))
                .map(event -> event.traceContext().runId()))
                .containsOnly(outboundRunId);
    }

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
                        agentFacade,
                        new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
                ),
                telegramOutboundClient,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
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
                        agentFacade,
                        new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
                ),
                telegramOutboundClient,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
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
                        agentFacade,
                        new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
                ),
                telegramOutboundClient,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
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

    /**
     * 用于记录 Telegram adapter 发出的观测事件，保证测试只验证结构化事件而不依赖真实日志格式。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 决定测试期间是否真正记录事件，便于在不关注可观测性的用例里模拟关闭模式。
         */
        private final RuntimeObservationMode mode;

        /**
         * 按时间顺序保留 Telegram 与 direct-message 主链路写出的事件，供测试断言顺序与关联性。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 通过显式构造参数固定观测模式，避免测试依赖应用级配置装配。
         */
        private RecordingRuntimeObservationPublisher(RuntimeObservationMode mode) {
            this.mode = mode;
        }

        /**
         * 为测试里的每一轮 Telegram 处理生成独立 trace，模拟生产环境的按次观测语义。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext(
                    "run-" + (events.size() + 1),
                    channel,
                    externalConversationId,
                    externalMessageId,
                    null,
                    mode
            );
        }

        /**
         * 记录没有详细负载的结构化事件，满足当前 Telegram adapter 的最小测试需求。
         */
        @Override
        public void emit(
                TraceContext traceContext,
                String eventType,
                RuntimeObservationPhase phase,
                RuntimeObservationLevel level,
                String component,
                Map<String, Object> payload
        ) {
            emit(traceContext, eventType, phase, level, component, payload, Map.of());
        }

        /**
         * 记录完整事件对象，让测试可以断言出站失败是否附着在同一 `runId` 上。
         */
        @Override
        public void emit(
                TraceContext traceContext,
                String eventType,
                RuntimeObservationPhase phase,
                RuntimeObservationLevel level,
                String component,
                Map<String, Object> payload,
                Map<String, Object> verbosePayload
        ) {
            if (mode == RuntimeObservationMode.OFF) {
                return;
            }
            events.add(new RuntimeObservationEvent(
                    Instant.parse("2026-04-04T08:00:00Z"),
                    eventType,
                    phase,
                    level,
                    component,
                    traceContext,
                    payload,
                    verbosePayload
            ));
        }
    }
}
