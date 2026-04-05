package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.api.AgentFacade;
import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.store.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.store.memory.InMemoryConversationDeliveryTargetRepository;
import com.quashy.openclaw4j.store.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.store.memory.InMemoryProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证开发用单聊入口在进入 Agent 前，对身份映射、活跃会话和消息幂等的编排是否稳定。
 */
class DirectMessageServiceTest {

    /**
     * 首次处理与已完成幂等跳过都必须产出带同轮 `runId` 的结构化事件，便于后续 console/Web UI 按次追踪。
     */
    @Test
    void shouldEmitCorrelatedObservationEventsForFirstProcessingAndProcessedDuplicate() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("ok", List.of()));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.TIMELINE);
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new InMemoryConversationDeliveryTargetRepository(),
                agentFacade,
                publisher
        );
        DirectMessageIngressCommand command = new DirectMessageIngressCommand("telegram", "user-1", "dm-1", "msg-1", "你好");

        service.handleWithMetadata(command);
        service.handleWithMetadata(command);

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentFacade).reply(requestCaptor.capture());
        assertThat(requestCaptor.getValue().traceContext().runId())
                .isEqualTo(publisher.events.get(0).traceContext().runId());
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .containsExactly(
                        "direct_message.received",
                        "direct_message.first_processing",
                        "direct_message.received",
                        "direct_message.duplicate_cached"
                );
        assertThat(publisher.events.subList(0, 2))
                .extracting(event -> event.traceContext().runId())
                .containsOnly(publisher.events.get(0).traceContext().runId());
        assertThat(publisher.events.subList(2, 4))
                .extracting(event -> event.traceContext().runId())
                .containsOnly(publisher.events.get(2).traceContext().runId());
        assertThat(publisher.events.get(0).traceContext().runId())
                .isNotEqualTo(publisher.events.get(2).traceContext().runId());
    }

    /**
     * 同一渠道同一外部用户的后续消息必须复用首次创建的内部用户与活跃会话，避免多轮上下文被意外切断。
     */
    @Test
    void shouldReuseInternalUserAndConversationForSameChannelUser() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("ok", List.of()));
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new InMemoryConversationDeliveryTargetRepository(),
                agentFacade,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
        );

        service.handle(new DirectMessageIngressCommand("dev", "user-1", "dm-1", "msg-1", "你好"));
        service.handle(new DirectMessageIngressCommand("dev", "user-1", "dm-1", "msg-2", "继续"));

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentFacade, times(2)).reply(requestCaptor.capture());
        List<AgentRequest> capturedRequests = requestCaptor.getAllValues();
        assertThat(capturedRequests.get(1).userId()).isEqualTo(capturedRequests.get(0).userId());
        assertThat(capturedRequests.get(1).conversationId()).isEqualTo(capturedRequests.get(0).conversationId());
    }

    /**
     * 相同外部消息被重复投递时必须直接返回首次结果，确保渠道重试不会再次触发模型调用。
     */
    @Test
    void shouldReturnExistingReplyForDuplicateMessage() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        ReplyEnvelope replyEnvelope = new ReplyEnvelope("第一次回复", List.of());
        when(agentFacade.reply(any())).thenReturn(replyEnvelope);
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new InMemoryConversationDeliveryTargetRepository(),
                agentFacade,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
        );

        ReplyEnvelope firstReply = service.handle(new DirectMessageIngressCommand("dev", "user-1", "dm-1", "msg-1", "你好"));
        ReplyEnvelope secondReply = service.handle(new DirectMessageIngressCommand("dev", "user-1", "dm-1", "msg-1", "你好"));

        assertThat(secondReply).isEqualTo(firstReply);
        verify(agentFacade, times(1)).reply(any());
    }

    /**
     * 同一外部消息并发重投时也必须只触发一次 Agent 调用，否则 webhook 重试会把同一条消息处理成两次不同回复。
     */
    @Test
    void shouldProcessConcurrentDuplicateMessageOnlyOnce() throws Exception {
        AgentFacade agentFacade = mock(AgentFacade.class);
        ReplyEnvelope replyEnvelope = new ReplyEnvelope("第一次回复", List.of());
        CountDownLatch firstInvocationStarted = new CountDownLatch(1);
        CountDownLatch allowInvocationToFinish = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();
        when(agentFacade.reply(any())).thenAnswer(invocation -> {
            int currentInvocationCount = invocationCount.incrementAndGet();
            if (currentInvocationCount == 1) {
                firstInvocationStarted.countDown();
            }
            if (!allowInvocationToFinish.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待测试放行超时");
            }
            return replyEnvelope;
        });
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new InMemoryConversationDeliveryTargetRepository(),
                agentFacade,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
        );
        DirectMessageIngressCommand command = new DirectMessageIngressCommand("dev", "user-1", "dm-1", "msg-1", "你好");
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<ReplyEnvelope> firstReply = executorService.submit(() -> service.handle(command));
            assertThat(firstInvocationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<ReplyEnvelope> secondReply = executorService.submit(() -> service.handle(command));

            Thread.sleep(150);

            assertThat(invocationCount.get()).isEqualTo(1);
            allowInvocationToFinish.countDown();
            assertThat(firstReply.get(2, TimeUnit.SECONDS)).isEqualTo(replyEnvelope);
            assertThat(secondReply.get(2, TimeUnit.SECONDS)).isEqualTo(replyEnvelope);
        } finally {
            allowInvocationToFinish.countDown();
            executorService.shutdownNow();
        }

        verify(agentFacade, times(1)).reply(any());
    }

    /**
     * 完成内部会话解析后必须同步刷新回发目标绑定，确保 reminder 之类的异步能力能仅凭内部会话回到正确渠道会话。
     */
    @Test
    void shouldRefreshConversationDeliveryTargetAfterConversationResolution() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("ok", List.of()));
        InMemoryConversationDeliveryTargetRepository deliveryTargetRepository = new InMemoryConversationDeliveryTargetRepository();
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                deliveryTargetRepository,
                agentFacade,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF)
        );

        service.handle(new DirectMessageIngressCommand("telegram", "user-1", "dm-1", "msg-1", "你好"));

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentFacade).reply(requestCaptor.capture());
        assertThat(deliveryTargetRepository.findByConversationId(requestCaptor.getValue().conversationId()))
                .hasValueSatisfying(target -> {
                    assertThat(target).isEqualTo(new ConversationDeliveryTarget(
                            requestCaptor.getValue().conversationId(),
                            "telegram",
                            "dm-1",
                            target.updatedAt()
                    ));
                });
    }

    /**
     * 用最小内存实现记录 direct-message 主链路发出的事件，避免测试依赖真实控制台 sink。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 控制当前测试实例希望模拟的观测模式，便于复用同一记录器覆盖开启与关闭场景。
         */
        private final RuntimeObservationMode mode;

        /**
         * 顺序保存链路中发出的事件，便于断言事件类型、顺序和 `runId` 关联关系。
         */
        private final List<RuntimeObservationEvent> events = new java.util.ArrayList<>();

        /**
         * 在测试里显式声明模式，而不是依赖配置装配，避免让行为断言受 Spring 环境影响。
         */
        private RecordingRuntimeObservationPublisher(RuntimeObservationMode mode) {
            this.mode = mode;
        }

        /**
         * 为每次单聊 ingress 调用生成一个测试可控的 trace 上下文，模拟生产环境中的按次关联语义。
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
         * 记录仅包含摘要负载的事件，便于覆盖默认时间线与关闭模式下的基本行为。
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
         * 记录摘要与详细负载，保持与生产发布器相同的方法契约，方便后续测试直接复用。
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
