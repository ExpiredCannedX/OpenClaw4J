package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.repository.ConversationTurnRepository;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提供当前 change 所需的最小 Agent 主链路实现，负责 workspace 加载、上下文组装、模型调用和失败兜底。
 */
@Service
public class DefaultAgentFacade implements AgentFacade {

    /**
     * 负责读取本次推理需要的 workspace 快照，隔离文件系统细节。
     */
    private final WorkspaceLoader workspaceLoader;

    /**
     * 负责把 workspace 与 recent turns 组装成稳定提示词，避免主流程掺杂格式细节。
     */
    private final AgentPromptAssembler promptAssembler;

    /**
     * 保存和读取最近会话轮次，支撑单聊多轮上下文连续。
     */
    private final ConversationTurnRepository conversationTurnRepository;

    /**
     * 统一封装底层模型调用，使主链路可以独立于具体模型 SDK 测试和演进。
     */
    private final AgentModelClient agentModelClient;

    /**
     * 提供 workspace 路径、recent turn 数量和失败兜底文案等当前阶段必须配置。
     */
    private final OpenClawProperties properties;

    /**
     * 通过显式依赖注入固定主链路边界，让渠道层与存储层都不需要知道模型调用和上下文组装的细节。
     */
    public DefaultAgentFacade(
            WorkspaceLoader workspaceLoader,
            AgentPromptAssembler promptAssembler,
            ConversationTurnRepository conversationTurnRepository,
            AgentModelClient agentModelClient,
            OpenClawProperties properties
    ) {
        this.workspaceLoader = workspaceLoader;
        this.promptAssembler = promptAssembler;
        this.conversationTurnRepository = conversationTurnRepository;
        this.agentModelClient = agentModelClient;
        this.properties = properties;
    }

    /**
     * 执行一次完整的最小单聊推理流程，并确保无论模型成功还是失败，都返回统一结构的 `ReplyEnvelope`。
     */
    @Override
    public ReplyEnvelope reply(AgentRequest request) {
        WorkspaceSnapshot workspaceSnapshot = workspaceLoader.load();
        List<ConversationTurn> recentTurns = conversationTurnRepository.loadRecentTurns(request.conversationId(), properties.recentTurnLimit());
        AgentPrompt prompt = promptAssembler.assemble(workspaceSnapshot, recentTurns, request.message());
        conversationTurnRepository.appendTurn(request.conversationId(), ConversationTurn.user(request.message().body()));
        try {
            String replyBody = agentModelClient.generate(prompt);
            conversationTurnRepository.appendTurn(request.conversationId(), ConversationTurn.assistant(replyBody));
            return new ReplyEnvelope(replyBody, List.of());
        } catch (Exception exception) {
            conversationTurnRepository.appendTurn(request.conversationId(), ConversationTurn.assistant(properties.fallbackReply()));
            return new ReplyEnvelope(properties.fallbackReply(), List.of());
        }
    }
}
