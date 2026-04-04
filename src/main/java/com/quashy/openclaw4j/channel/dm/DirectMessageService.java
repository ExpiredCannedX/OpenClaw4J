package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.api.AgentFacade;
import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.repository.ActiveConversationRepository;
import com.quashy.openclaw4j.repository.IdentityMappingRepository;
import com.quashy.openclaw4j.repository.ProcessedMessageRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排统一单聊 ingress 的身份映射、活跃会话复用和消息幂等逻辑，让各渠道 adapter 只负责协议翻译。
 */
@Service
public class DirectMessageService {

    /**
     * 管理外部用户到内部用户的映射，确保渠道身份不会直接泄漏到核心域模型。
     */
    private final IdentityMappingRepository identityMappingRepository;

    /**
     * 管理“同一渠道同一用户唯一活跃会话”策略对应的内部会话标识。
     */
    private final ActiveConversationRepository activeConversationRepository;

    /**
     * 记录已处理过的外部消息，避免渠道重试导致重复模型调用。
     */
    private final ProcessedMessageRepository processedMessageRepository;

    /**
     * 统一 Agent 入口，负责后续的上下文加载、模型调用和结构化回复生成。
     */
    private final AgentFacade agentFacade;

    /**
     * 负责创建 trace 并发布 direct-message 边界事件，避免各个渠道重复手写观测逻辑。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 记录正在处理中但尚未落入最终幂等缓存的消息结果，防止 webhook 并发重投把同一条消息送入 Agent 两次。
     */
    private final Map<String, CompletableFuture<ReplyEnvelope>> inFlightReplies = new ConcurrentHashMap<>();

    /**
     * 通过仓储接口组合当前单聊主链路的最小依赖，避免控制器直接管理内部身份和幂等状态。
     */
    public DirectMessageService(
            IdentityMappingRepository identityMappingRepository,
            ActiveConversationRepository activeConversationRepository,
            ProcessedMessageRepository processedMessageRepository,
            AgentFacade agentFacade,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.identityMappingRepository = identityMappingRepository;
        this.activeConversationRepository = activeConversationRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.agentFacade = agentFacade;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 处理一条已经标准化的单聊入站命令；若命中重复投递则直接返回旧结果，否则完成身份映射与 Agent 调用后再记录幂等结果。
     */
    public ReplyEnvelope handle(DirectMessageIngressCommand request) {
        return handleWithMetadata(request).replyEnvelope();
    }

    /**
     * 在返回统一回复的同时暴露“是否首次处理成功”的元信息，供 Telegram 等渠道在重复 webhook 投递时避免再次发送旧结果。
     */
    public DirectMessageHandleResult handleWithMetadata(DirectMessageIngressCommand request) {
        return handleWithMetadata(
                request,
                runtimeObservationPublisher.createTrace(request.channel(), request.externalConversationId(), request.externalMessageId())
        );
    }

    /**
     * 使用调用方已创建好的 trace 上下文处理一次统一单聊 ingress，保证 Telegram 与 Agent Core 能共享同一 `runId`。
     */
    public DirectMessageHandleResult handleWithMetadata(DirectMessageIngressCommand request, TraceContext traceContext) {
        runtimeObservationPublisher.emit(
                traceContext,
                "direct_message.received",
                RuntimeObservationPhase.INGRESS,
                RuntimeObservationLevel.INFO,
                "DirectMessageService",
                Map.of(
                        "channel", request.channel(),
                        "externalConversationId", request.externalConversationId(),
                        "externalMessageId", request.externalMessageId()
                )
        );
        Optional<ReplyEnvelope> existingReply = processedMessageRepository.findProcessedReply(request.channel(), request.externalMessageId());
        if (existingReply.isPresent()) {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "direct_message.duplicate_cached",
                    RuntimeObservationPhase.IDEMPOTENCY,
                    RuntimeObservationLevel.WARN,
                    "DirectMessageService",
                    Map.of("reason", "processed_cache_hit")
            );
            return new DirectMessageHandleResult(existingReply.get(), false, traceContext);
        }
        String messageKey = buildMessageKey(request.channel(), request.externalMessageId());
        CompletableFuture<ReplyEnvelope> newInFlightReply = new CompletableFuture<>();
        CompletableFuture<ReplyEnvelope> existingInFlightReply = inFlightReplies.putIfAbsent(messageKey, newInFlightReply);
        if (existingInFlightReply != null) {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "direct_message.duplicate_inflight",
                    RuntimeObservationPhase.IDEMPOTENCY,
                    RuntimeObservationLevel.WARN,
                    "DirectMessageService",
                    Map.of("reason", "in_flight_reused")
            );
            return new DirectMessageHandleResult(awaitInFlightReply(existingInFlightReply), false, traceContext);
        }
        try {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "direct_message.first_processing",
                    RuntimeObservationPhase.INGRESS,
                    RuntimeObservationLevel.INFO,
                    "DirectMessageService",
                    Map.of("channel", request.channel())
            );
            FirstProcessingResult firstProcessingResult = processNewMessage(request, traceContext);
            ReplyEnvelope replyEnvelope = firstProcessingResult.replyEnvelope();
            processedMessageRepository.saveProcessedReply(request.channel(), request.externalMessageId(), replyEnvelope);
            newInFlightReply.complete(replyEnvelope);
            return new DirectMessageHandleResult(replyEnvelope, true, firstProcessingResult.traceContext());
        } catch (RuntimeException exception) {
            newInFlightReply.completeExceptionally(exception);
            runtimeObservationPublisher.emit(
                    traceContext,
                    "direct_message.failed",
                    RuntimeObservationPhase.INGRESS,
                    RuntimeObservationLevel.ERROR,
                    "DirectMessageService",
                    Map.of("exceptionType", exception.getClass().getSimpleName()),
                    buildExceptionVerbosePayload(exception)
            );
            throw exception;
        } finally {
            inFlightReplies.remove(messageKey, newInFlightReply);
        }
    }

    /**
     * 真正执行首次消息处理流程，只在确认当前消息不属于已完成或进行中的重复投递后才调用 Agent。
     */
    private FirstProcessingResult processNewMessage(DirectMessageIngressCommand request, TraceContext traceContext) {
        NormalizedDirectMessage message = new NormalizedDirectMessage(
                request.channel(),
                request.externalUserId(),
                request.externalConversationId(),
                request.externalMessageId(),
                request.body()
        );
        InternalUserId userId = identityMappingRepository.getOrCreateInternalUserId(request.channel(), request.externalUserId());
        InternalConversationId conversationId = activeConversationRepository.getOrCreateActiveConversation(request.channel(), request.externalUserId());
        TraceContext enrichedTraceContext = traceContext.withInternalConversationId(conversationId.value());
        return new FirstProcessingResult(agentFacade.reply(new AgentRequest(userId, conversationId, message, enrichedTraceContext)), enrichedTraceContext);
    }

    /**
     * 等待同一消息的首个处理中请求完成，并把其成功或失败结果透明传递给后续重复投递。
     */
    private ReplyEnvelope awaitInFlightReply(CompletableFuture<ReplyEnvelope> inFlightReply) {
        try {
            return inFlightReply.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    /**
     * 统一生成渠道级消息幂等键，避免各处自行拼接导致重复投递判定口径不一致。
     */
    private String buildMessageKey(String channel, String externalMessageId) {
        return channel + "::" + externalMessageId;
    }

    /**
     * 把异常的可选调试细节收敛到详细负载中，避免默认时间线模式直接打印完整异常文本。
     */
    private Map<String, Object> buildExceptionVerbosePayload(RuntimeException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            payload.put("exceptionMessage", exception.getMessage());
        }
        return payload;
    }

    /**
     * 承载首次处理路径返回的回复和增强后的 trace 上下文，避免内部会话标识在后续出站阶段丢失。
     */
    private record FirstProcessingResult(
            /**
             * 保存本次首次处理真正生成的统一回复，用于幂等缓存和渠道返回。
             */
            ReplyEnvelope replyEnvelope,
            /**
             * 保存已经补齐内部会话标识的 trace 上下文，供 Telegram 等后续边界继续复用。
             */
            TraceContext traceContext
    ) {
    }
}
