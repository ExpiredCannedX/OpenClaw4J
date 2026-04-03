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

import java.util.Optional;

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
        Optional<ReplyEnvelope> existingReply = processedMessageRepository.findProcessedReply(request.channel(), request.externalMessageId());
        if (existingReply.isPresent()) {
            return existingReply.get();
        }
        NormalizedDirectMessage message = new NormalizedDirectMessage(
                request.channel(),
                request.externalUserId(),
                request.externalConversationId(),
                request.externalMessageId(),
                request.body()
        );
        InternalUserId userId = identityMappingRepository.getOrCreateInternalUserId(request.channel(), request.externalUserId());
        InternalConversationId conversationId = activeConversationRepository.getOrCreateActiveConversation(request.channel(), request.externalUserId());
        ReplyEnvelope replyEnvelope = agentFacade.reply(new AgentRequest(userId, conversationId, message));
        processedMessageRepository.saveProcessedReply(request.channel(), request.externalMessageId(), replyEnvelope);
        return replyEnvelope;
    }
}
