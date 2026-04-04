package com.quashy.openclaw4j.agent.runtime;

import com.quashy.openclaw4j.agent.api.AgentFacade;
import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.agent.decision.AgentModelDecision;
import com.quashy.openclaw4j.agent.decision.FinalReplyDecision;
import com.quashy.openclaw4j.agent.decision.ToolCallDecision;
import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.prompt.AgentPromptAssembler;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.domain.ReplySignal;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.repository.ConversationTurnRepository;
import com.quashy.openclaw4j.skill.ResolvedSkill;
import com.quashy.openclaw4j.skill.SkillResolver;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import com.quashy.openclaw4j.tool.api.ToolExecutor;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
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
     * 负责在 Agent Core 稳定边界发布结构化运行事件，避免主链路直接依赖具体 sink 细节。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

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
            OpenClawProperties properties,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.workspaceLoader = workspaceLoader;
        this.promptAssembler = promptAssembler;
        this.skillResolver = skillResolver;
        this.conversationTurnRepository = conversationTurnRepository;
        this.agentModelClient = agentModelClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 执行一次完整的最小单聊推理流程，并在“直接回复”与“单次工具调用”之间进行有界闭环。
     */
    @Override
    public ReplyEnvelope reply(AgentRequest request) {
        TraceContext traceContext = resolveTraceContext(request);
        runtimeObservationPublisher.emit(
                traceContext,
                "agent.run.started",
                RuntimeObservationPhase.AGENT,
                RuntimeObservationLevel.INFO,
                "DefaultAgentFacade",
                Map.of("conversationId", request.conversationId().value())
        );

        String failureStage = "workspace_load";
        try {
            // 加载 workspace/ 目录下的所有静态知识，包括规则 (SOUL.md, USER.md 等)、记忆 (memory/) 和技能定义 (skills/)，形成一个“世界知识”快照。
            WorkspaceSnapshot workspaceSnapshot = workspaceLoader.load();

            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.workspace.loaded",
                    RuntimeObservationPhase.WORKSPACE,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    Map.of(
                            "workspaceFileCount", workspaceSnapshot.staticRules().size(),
                            "memoryFileCount", workspaceSnapshot.dynamicMemories().size(),
                            "localSkillCount", workspaceSnapshot.localSkillDocuments().size()
                    )
            );

            failureStage = "skill_resolve";
            // 根据用户当前的消息，判断是否命中了某个在 skills/ 目录中定义的特定技能，以便在思考时使用专门的指令。
            Optional<ResolvedSkill> selectedSkill = skillResolver.resolve(
                    request.message().body(),
                    workspaceSnapshot.localSkillDocuments()
            );
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.skill.resolved",
                    RuntimeObservationPhase.SKILL,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    buildSkillPayload(selectedSkill)
            );

            failureStage = "recent_turns_load";
            // 从数据库或内存中读取当前对话的最近几轮交流，作为短期记忆。
            List<ConversationTurn> recentTurns = conversationTurnRepository.loadRecentTurns(request.conversationId(), properties.recentTurnLimit());
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.recent_turns.loaded",
                    RuntimeObservationPhase.AGENT,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    Map.of("recentTurnCount", recentTurns.size())
            );

            conversationTurnRepository.appendTurn(request.conversationId(), ConversationTurn.user(request.message().body()));
            failureStage = "model_decision";
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.model.decision.started",
                    RuntimeObservationPhase.MODEL,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    Map.of("toolDefinitionCount", toolRegistry.listDefinitions().size())
            );

            // promptAssembler 会把工作区快照、会话历史、解析出的技能、最新的用户消息以及所有可用工具的定义组合成一个完整的、结构化的提示词 (Prompt)。
            // 将组装好的提示词发送给大语言模型 (LLM)，让模型来决定下一步行动。这个决策 (AgentModelDecision) 主要有两种可能：
            // 1.FinalReplyDecision: 模型认为信息已经足够，可以直接生成最终回复。
            // 2.ToolCallDecision: 模型认为需要调用一个工具 (Tool) 来获取额外信息或执行某个操作。
            AgentModelDecision decision = agentModelClient.decideNextAction(promptAssembler.assemblePlanningPrompt(
                    workspaceSnapshot,
                    selectedSkill,
                    recentTurns,
                    request.message(),
                    toolRegistry.listDefinitions()
            ));
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.model.decision.completed",
                    RuntimeObservationPhase.MODEL,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    buildDecisionPayload(decision)
            );
            String replyBody;

            // 如果决策是“直接回复”:
            // 流程: 直接从 FinalReplyDecision 中提取回复文本。
            if (decision instanceof FinalReplyDecision finalReplyDecision) {
                replyBody = finalReplyDecision.reply();
            } else {
                // 如果决策是“单次工具调用”:
                ToolCallDecision toolCallDecision = (ToolCallDecision) decision;
                failureStage = "tool_execution";
                runtimeObservationPublisher.emit(
                        traceContext,
                        "agent.tool.execution.started",
                        RuntimeObservationPhase.TOOL,
                        RuntimeObservationLevel.INFO,
                        "DefaultAgentFacade",
                        Map.of("toolName", toolCallDecision.toolName())
                );
                // 根据 ToolCallDecision 中的指令，执行相应的工具代码，并获得一个执行结果 (ToolExecutionResult)。
                ToolExecutionResult observation = toolExecutor.execute(new ToolCallRequest(
                        toolCallDecision.toolName(),
                        toolCallDecision.arguments()
                ));

                runtimeObservationPublisher.emit(
                        traceContext,
                        "agent.tool.execution.completed",
                        RuntimeObservationPhase.TOOL,
                        RuntimeObservationLevel.INFO,
                        "DefaultAgentFacade",
                        buildToolObservationPayload(observation),
                        buildToolObservationVerbosePayload(observation)
                );
                failureStage = "final_reply_generation";
                runtimeObservationPublisher.emit(
                        traceContext,
                        "agent.model.final_reply.started",
                        RuntimeObservationPhase.REPLY,
                        RuntimeObservationLevel.INFO,
                        "DefaultAgentFacade",
                        Map.of("toolName", toolCallDecision.toolName())
                );

                // promptAssembler 会把原始上下文、工具调用结果等信息再次组装成一个新的提示词，发送给模型，要求它基于工具返回的信息生成最终的人类可读的回复。
                replyBody = agentModelClient.generateFinalReply(promptAssembler.assembleFinalReplyPrompt(
                        workspaceSnapshot,
                        selectedSkill,
                        recentTurns,
                        request.message(),
                        observation
                ));

                runtimeObservationPublisher.emit(
                        traceContext,
                        "agent.model.final_reply.completed",
                        RuntimeObservationPhase.REPLY,
                        RuntimeObservationLevel.INFO,
                        "DefaultAgentFacade",
                        Map.of("replyLength", StringUtils.hasText(replyBody) ? replyBody.length() : 0),
                        buildReplyVerbosePayload(replyBody)
                );
            }

            ReplyEnvelope replyEnvelope = buildReplyEnvelope(request.conversationId(), replyBody, selectedSkill, traceContext);
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.run.completed",
                    RuntimeObservationPhase.AGENT,
                    RuntimeObservationLevel.INFO,
                    "DefaultAgentFacade",
                    Map.of("replyLength", replyEnvelope.body().length())
            );
            return replyEnvelope;
        } catch (Exception exception) {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "agent.run.failed",
                    RuntimeObservationPhase.AGENT,
                    RuntimeObservationLevel.ERROR,
                    "DefaultAgentFacade",
                    Map.of(
                            "stage", failureStage,
                            "exceptionType", exception.getClass().getSimpleName()
                    ),
                    buildExceptionVerbosePayload(exception)
            );
            return fallbackReply(request.conversationId(), traceContext, failureStage);
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
    private ReplyEnvelope buildReplyEnvelope(
            InternalConversationId conversationId,
            String replyBody,
            Optional<ResolvedSkill> selectedSkill,
            TraceContext traceContext
    ) {
        if (!StringUtils.hasText(replyBody)) {
            return fallbackReply(conversationId, traceContext, "blank_reply");
        }
        conversationTurnRepository.appendTurn(conversationId, ConversationTurn.assistant(replyBody));
        runtimeObservationPublisher.emit(
                traceContext,
                "agent.reply.completed",
                RuntimeObservationPhase.REPLY,
                RuntimeObservationLevel.INFO,
                "DefaultAgentFacade",
                Map.of("replyLength", replyBody.length()),
                buildReplyVerbosePayload(replyBody)
        );
        return new ReplyEnvelope(replyBody, buildSignals(selectedSkill));
    }

    /**
     * 统一落盘并返回兜底回复，确保所有失败路径对渠道层都保持相同协议。
     */
    private ReplyEnvelope fallbackReply(InternalConversationId conversationId, TraceContext traceContext, String reason) {
        conversationTurnRepository.appendTurn(conversationId, ConversationTurn.assistant(properties.fallbackReply()));
        runtimeObservationPublisher.emit(
                traceContext,
                "agent.reply.fallback",
                RuntimeObservationPhase.REPLY,
                RuntimeObservationLevel.WARN,
                "DefaultAgentFacade",
                Map.of("reason", reason)
        );
        return new ReplyEnvelope(properties.fallbackReply(), List.of());
    }

    /**
     * 在渠道层未显式传入 trace 时按当前消息创建兜底上下文，并补齐内部会话标识。
     */
    private TraceContext resolveTraceContext(AgentRequest request) {
        TraceContext traceContext = request.traceContext() != null
                ? request.traceContext()
                : runtimeObservationPublisher.createTrace(
                request.message().channel(),
                request.message().externalConversationId(),
                request.message().externalMessageId()
        );
        return traceContext.withInternalConversationId(request.conversationId().value());
    }

    /**
     * 为 Skill 解析事件构造摘要负载，确保时间线能明确显示是否命中以及命中的 Skill 名称。
     */
    private Map<String, Object> buildSkillPayload(Optional<ResolvedSkill> selectedSkill) {
        if (selectedSkill.isEmpty()) {
            return Map.of("matched", false);
        }
        return Map.of(
                "matched", true,
                "skillName", selectedSkill.get().skillName(),
                "activationMode", selectedSkill.get().activationMode()
        );
    }

    /**
     * 为模型决策事件构造摘要负载，使时间线能直接看出是最终回复还是工具调用。
     */
    private Map<String, Object> buildDecisionPayload(AgentModelDecision decision) {
        if (decision instanceof FinalReplyDecision) {
            return Map.of("decisionType", "final_reply");
        }
        ToolCallDecision toolCallDecision = (ToolCallDecision) decision;
        return Map.of(
                "decisionType", "tool_call",
                "toolName", toolCallDecision.toolName()
        );
    }

    /**
     * 为工具观察结果构造摘要字段，避免默认时间线直接暴露完整 payload 结构。
     */
    private Map<String, Object> buildToolObservationPayload(ToolExecutionResult observation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", observation.toolName());
        if (observation instanceof com.quashy.openclaw4j.tool.model.ToolExecutionSuccess) {
            payload.put("status", "success");
        } else {
            payload.put("status", "error");
            payload.put("errorCode", ((com.quashy.openclaw4j.tool.model.ToolExecutionError) observation).errorCode());
        }
        return Map.copyOf(payload);
    }

    /**
     * 仅在详细模式下提供工具结果预览，避免默认模式直接打印完整工具输出。
     */
    private Map<String, Object> buildToolObservationVerbosePayload(ToolExecutionResult observation) {
        if (observation instanceof com.quashy.openclaw4j.tool.model.ToolExecutionSuccess success) {
            return Map.of("observationPreview", success.payload().toString());
        }
        com.quashy.openclaw4j.tool.model.ToolExecutionError error = (com.quashy.openclaw4j.tool.model.ToolExecutionError) observation;
        return Map.of("observationPreview", error.message());
    }

    /**
     * 仅在详细模式下暴露回复预览，帮助排障时查看模型输出而不污染默认时间线。
     */
    private Map<String, Object> buildReplyVerbosePayload(String replyBody) {
        if (!StringUtils.hasText(replyBody)) {
            return Map.of();
        }
        return Map.of("replyPreview", replyBody);
    }

    /**
     * 把异常的可选文本放入详细负载，避免默认时间线模式直接输出过长的堆栈或错误文案。
     */
    private Map<String, Object> buildExceptionVerbosePayload(Exception exception) {
        if (!StringUtils.hasText(exception.getMessage())) {
            return Map.of();
        }
        return Map.of("exceptionMessage", exception.getMessage());
    }
}
