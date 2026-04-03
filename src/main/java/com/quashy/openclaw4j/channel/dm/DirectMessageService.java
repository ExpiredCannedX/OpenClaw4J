package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.AgentFacade;
import com.quashy.openclaw4j.agent.AgentRequest;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.repository.ActiveConversationRepository;
import com.quashy.openclaw4j.repository.IdentityMappingRepository;
import com.quashy.openclaw4j.repository.ProcessedMessageRepository;
import org.springframework.stereotype.Service;

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
            AgentFacade agentFacade
    ) {
        this.identityMappingRepository = identityMappingRepository;
        this.activeConversationRepository = activeConversationRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.agentFacade = agentFacade;
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
        Optional<ReplyEnvelope> existingReply = processedMessageRepository.findProcessedReply(request.channel(), request.externalMessageId());
        if (existingReply.isPresent()) {
            return new DirectMessageHandleResult(existingReply.get(), false);
        }
        String messageKey = buildMessageKey(request.channel(), request.externalMessageId());
        CompletableFuture<ReplyEnvelope> newInFlightReply = new CompletableFuture<>();
        CompletableFuture<ReplyEnvelope> existingInFlightReply = inFlightReplies.putIfAbsent(messageKey, newInFlightReply);
        if (existingInFlightReply != null) {
            return new DirectMessageHandleResult(awaitInFlightReply(existingInFlightReply), false);
        }
        try {
            ReplyEnvelope replyEnvelope = processNewMessage(request);
            processedMessageRepository.saveProcessedReply(request.channel(), request.externalMessageId(), replyEnvelope);
            newInFlightReply.complete(replyEnvelope);
            return new DirectMessageHandleResult(replyEnvelope, true);
        } catch (RuntimeException exception) {
            newInFlightReply.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlightReplies.remove(messageKey, newInFlightReply);
        }
    }

    /**
     * 真正执行首次消息处理流程，只在确认当前消息不属于已完成或进行中的重复投递后才调用 Agent。
     */
    private ReplyEnvelope processNewMessage(DirectMessageIngressCommand request) {
        NormalizedDirectMessage message = new NormalizedDirectMessage(
                request.channel(),
                request.externalUserId(),
                request.externalConversationId(),
                request.externalMessageId(),
                request.body()
        );
        InternalUserId userId = identityMappingRepository.getOrCreateInternalUserId(request.channel(), request.externalUserId());
        InternalConversationId conversationId = activeConversationRepository.getOrCreateActiveConversation(request.channel(), request.externalUserId());
        return agentFacade.reply(new AgentRequest(userId, conversationId, message));
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
}
