package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.domain.ReplySignal;
import com.quashy.openclaw4j.repository.ConversationTurnRepository;
import com.quashy.openclaw4j.skill.ResolvedSkill;
import com.quashy.openclaw4j.skill.SkillResolver;
import com.quashy.openclaw4j.tool.ToolCallRequest;
import com.quashy.openclaw4j.tool.ToolExecutionResult;
import com.quashy.openclaw4j.tool.ToolExecutor;
import com.quashy.openclaw4j.tool.ToolRegistry;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * 负责根据本地 Skill 文档与当前消息解析至多一个可安全注入的 Skill。
     */
    private final SkillResolver skillResolver;

    /**
     * 保存和读取最近会话轮次，支撑单聊多轮上下文连续。
     */
    private final ConversationTurnRepository conversationTurnRepository;

    /**
     * 统一封装底层模型调用，使主链路可以独立于具体模型 SDK 测试和演进。
     */
    private final AgentModelClient agentModelClient;

    /**
     * 暴露当前请求可见的工具目录，供规划阶段 prompt 组装与工具按名解析使用。
     */
    private final ToolRegistry toolRegistry;

    /**
     * 负责执行一次同步工具调用并统一收敛执行结果，避免主链路处理工具异常细节。
     */
    private final ToolExecutor toolExecutor;

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
            SkillResolver skillResolver,
            ConversationTurnRepository conversationTurnRepository,
            AgentModelClient agentModelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            OpenClawProperties properties
    ) {
        this.workspaceLoader = workspaceLoader;
        this.promptAssembler = promptAssembler;
        this.skillResolver = skillResolver;
        this.conversationTurnRepository = conversationTurnRepository;
        this.agentModelClient = agentModelClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 执行一次完整的最小单聊推理流程，并在“直接回复”与“单次工具调用”之间进行有界闭环。
     */
    @Override
    public ReplyEnvelope reply(AgentRequest request) {
        WorkspaceSnapshot workspaceSnapshot = workspaceLoader.load();
        Optional<ResolvedSkill> selectedSkill = skillResolver.resolve(
                request.message().body(),
                workspaceSnapshot.localSkillDocuments()
        );
        List<ConversationTurn> recentTurns = conversationTurnRepository.loadRecentTurns(request.conversationId(), properties.recentTurnLimit());
        conversationTurnRepository.appendTurn(request.conversationId(), ConversationTurn.user(request.message().body()));
        try {
            AgentModelDecision decision = agentModelClient.decideNextAction(promptAssembler.assemblePlanningPrompt(
                    workspaceSnapshot,
                    selectedSkill,
                    recentTurns,
                    request.message(),
                    toolRegistry.listDefinitions()
            ));
            String replyBody = resolveReplyBody(workspaceSnapshot, selectedSkill, recentTurns, request, decision);
            return buildReplyEnvelope(request.conversationId(), replyBody, selectedSkill);
        } catch (Exception exception) {
            return fallbackReply(request.conversationId());
        }
    }

    /**
     * 只在本次请求确实命中 Skill 且成功完成时生成结构化 signal，避免把系统语义混入正文。
     */
    private List<ReplySignal> buildSignals(Optional<ResolvedSkill> selectedSkill) {
        return selectedSkill
                .map(skill -> List.of(new ReplySignal(
                        "skill_applied",
                        Map.of(
                                "skill_name", skill.skillName(),
                                "activation_mode", skill.activationMode()
                        )
                )))
                .orElseGet(List::of);
    }

    /**
     * 根据模型决策选择直接回复或一次工具调用闭环，把最终回复正文统一收敛成一个字符串结果。
     */
    private String resolveReplyBody(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            AgentRequest request,
            AgentModelDecision decision
    ) {
        if (decision instanceof FinalReplyDecision finalReplyDecision) {
            return finalReplyDecision.reply();
        }
        ToolCallDecision toolCallDecision = (ToolCallDecision) decision;
        ToolExecutionResult observation = toolExecutor.execute(new ToolCallRequest(
                toolCallDecision.toolName(),
                toolCallDecision.arguments()
        ));
        return agentModelClient.generateFinalReply(promptAssembler.assembleFinalReplyPrompt(
                workspaceSnapshot,
                selectedSkill,
                recentTurns,
                request.message(),
                observation
        ));
    }

    /**
     * 在最终正文非空时写入助手回复并返回正常结果，否则统一回退到系统兜底回复。
     */
    private ReplyEnvelope buildReplyEnvelope(
            InternalConversationId conversationId,
            String replyBody,
            Optional<ResolvedSkill> selectedSkill
    ) {
        if (!StringUtils.hasText(replyBody)) {
            return fallbackReply(conversationId);
        }
        conversationTurnRepository.appendTurn(conversationId, ConversationTurn.assistant(replyBody));
        return new ReplyEnvelope(replyBody, buildSignals(selectedSkill));
    }

    /**
     * 统一落盘并返回兜底回复，确保所有失败路径对渠道层都保持相同协议。
     */
    private ReplyEnvelope fallbackReply(InternalConversationId conversationId) {
        conversationTurnRepository.appendTurn(conversationId, ConversationTurn.assistant(properties.fallbackReply()));
        return new ReplyEnvelope(properties.fallbackReply(), List.of());
    }
}
