package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.agent.api.AgentRequest;
import com.quashy.openclaw4j.agent.decision.FinalReplyDecision;
import com.quashy.openclaw4j.agent.decision.ToolCallDecision;
import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.agent.prompt.AgentPromptAssembler;
import com.quashy.openclaw4j.agent.runtime.DefaultAgentFacade;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.skill.SkillMarkdownParser;
import com.quashy.openclaw4j.skill.SkillResolver;
import com.quashy.openclaw4j.store.memory.InMemoryConversationTurnRepository;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import com.quashy.openclaw4j.tool.builtin.time.TimeTool;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolExecutor;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Agent Core 在加入单次工具调用闭环后，仍能稳定完成 prompt 组装、工具观察回填和安全兜底。
 */
class DefaultAgentFacadeTest {

    /**
     * 工具闭环路径必须产出覆盖 workspace、模型决策、工具执行和最终回复的连续时间线，并保持同一 `runId` 关联。
     */
    @Test
    void shouldEmitObservationTimelineForToolBackedRunWithoutPollutingReplyEnvelope() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("time", Map.of()));
        when(modelClient.generateFinalReply(any())).thenReturn("现在是北京时间 2026-04-03 16:09:10。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.TIMELINE);
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                turnRepository,
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher
        );
        TraceContext traceContext = new TraceContext("run-1", "dev", "dm-2", "msg-2", null, RuntimeObservationMode.TIMELINE);

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-2"),
                new NormalizedDirectMessage("dev", "user-1", "dm-2", "msg-2", "现在几点了？"),
                traceContext
        ));

        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .containsSubsequence(
                        "agent.run.started",
                        "agent.workspace.loaded",
                        "agent.skill.resolved",
                        "agent.model.decision.completed",
                        "agent.tool.execution.completed",
                        "agent.model.final_reply.completed",
                        "agent.reply.completed",
                        "agent.run.completed"
                );
        assertThat(publisher.events)
                .extracting(event -> event.traceContext().runId())
                .containsOnly("run-1");
        assertThat(replyEnvelope.signals()).isEmpty();
    }

    /**
     * 模型直接返回最终回复时，系统必须把 Skill、最近会话和可用工具目录一并暴露给规划阶段，同时保持单次回复语义。
     */
    @Test
    void shouldExposeSkillConversationAndToolsDuringPlanningPrompt() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new FinalReplyDecision("最终回复"));
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        InternalConversationId conversationId = new InternalConversationId("conversation-1");
        turnRepository.appendTurn(conversationId, ConversationTurn.user("上一轮用户消息"));
        turnRepository.appendTurn(conversationId, ConversationTurn.assistant("上一轮助手消息"));
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                conversationId,
                new NormalizedDirectMessage("dev", "user-1", "dm-1", "msg-1", "请使用 $code-review 处理这一轮问题"),
                new TraceContext("run-1", "dev", "dm-1", "msg-1", "conversation-1", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> promptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).decideNextAction(promptCaptor.capture());
        verify(modelClient, never()).generateFinalReply(any());
        assertThat(promptCaptor.getValue().content())
                .contains("【静态规则】")
                .contains("规则")
                .contains("技能总览")
                .contains("偏好")
                .contains("记忆")
                .contains("先看风险，再给建议。")
                .contains("上一轮用户消息")
                .contains("上一轮助手消息")
                .contains("【可用工具目录】")
                .contains("name: time")
                .contains("requires: []")
                .contains("请使用 $code-review 处理这一轮问题");
        assertThat(replyEnvelope.body()).isEqualTo("最终回复");
        assertThat(replyEnvelope.signals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type()).isEqualTo("skill_applied");
                    assertThat(signal.payload()).containsEntry("skill_name", "code-review");
                    assertThat(signal.payload()).containsEntry("activation_mode", "explicit");
                });
        assertThat(turnRepository.loadRecentTurns(conversationId, 10))
                .extracting(ConversationTurn::content)
                .containsExactly("上一轮用户消息", "上一轮助手消息", "请使用 $code-review 处理这一轮问题", "最终回复");
    }

    /**
     * 模型请求 `time` 工具时，系统必须执行一次工具并把结构化观察结果注入最终回复阶段的 prompt。
     */
    @Test
    void shouldExecuteTimeToolAndIncludeObservationBeforeFinalReply() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("time", Map.of()));
        when(modelClient.generateFinalReply(any())).thenReturn("现在是北京时间 2026-04-03 16:09:10。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-2"),
                new NormalizedDirectMessage("dev", "user-1", "dm-2", "msg-2", "现在几点了？"),
                new TraceContext("run-2", "dev", "dm-2", "msg-2", "conversation-2", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("【工具观察结果】")
                .contains("tool_name: time")
                .contains("status: success")
                .contains("2026-04-03T16:09:10+08:00")
                .contains("Asia/Shanghai");
        assertThat(replyEnvelope.body()).isEqualTo("现在是北京时间 2026-04-03 16:09:10。");
        assertThat(replyEnvelope.signals()).isEmpty();
        assertThat(turnRepository.loadRecentTurns(new InternalConversationId("conversation-2"), 10))
                .extracting(ConversationTurn::content)
                .containsExactly("现在几点了？", "现在是北京时间 2026-04-03 16:09:10。");
    }

    /**
     * 模型请求不存在的工具时，系统必须把问题收敛为结构化工具错误观察，而不是把异常直接抛给渠道层。
     */
    @Test
    void shouldConvertMissingToolRequestIntoStructuredObservation() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("missing-tool", Map.of()));
        when(modelClient.generateFinalReply(any())).thenReturn("我当前无法使用该工具，但可以继续帮助你分析问题。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-3"),
                new NormalizedDirectMessage("dev", "user-1", "dm-3", "msg-3", "帮我调用一个还没接入的工具"),
                new TraceContext("run-3", "dev", "dm-3", "msg-3", "conversation-3", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: missing-tool")
                .contains("status: error")
                .contains("error_code: tool_not_found");
        assertThat(replyEnvelope.body()).isEqualTo("我当前无法使用该工具，但可以继续帮助你分析问题。");
    }

    /**
     * 工具执行抛出异常时，系统必须把异常转换成结构化观察，再继续收敛到最终回复而不是中断主链路。
     */
    @Test
    void shouldConvertToolFailureIntoStructuredObservation() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("broken", Map.of("mode", "now")));
        when(modelClient.generateFinalReply(any())).thenReturn("工具执行失败了，我先给你一个安全回复。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        Tool brokenTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "broken",
                        "用于测试执行失败的工具。",
                        ToolInputSchema.object(
                                Map.of("mode", new ToolInputProperty("string", "决定执行模式。")),
                                List.of("mode")
                        )
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                throw new IllegalStateException("boom");
            }
        };
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(brokenTool));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-4"),
                new NormalizedDirectMessage("dev", "user-1", "dm-4", "msg-4", "请尝试坏掉的工具"),
                new TraceContext("run-4", "dev", "dm-4", "msg-4", "conversation-4", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: broken")
                .contains("status: error")
                .contains("error_code: execution_failed");
        assertThat(replyEnvelope.body()).isEqualTo("工具执行失败了，我先给你一个安全回复。");
    }

    /**
     * 规划阶段模型调用失败时必须回退到统一兜底回复，避免把底层异常暴露给渠道层。
     */
    @Test
    void shouldReturnFallbackReplyWhenPlanningFails() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenThrow(new IllegalStateException("boom"));
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-5"),
                new NormalizedDirectMessage("dev", "user-1", "dm-5", "msg-5", "这一轮问题"),
                new TraceContext("run-5", "dev", "dm-5", "msg-5", "conversation-5", RuntimeObservationMode.OFF)
        ));

        assertThat(replyEnvelope.body()).isEqualTo("系统暂时繁忙，请稍后再试。");
        assertThat(replyEnvelope.signals()).isEmpty();
        assertThat(turnRepository.loadRecentTurns(new InternalConversationId("conversation-5"), 10))
                .extracting(ConversationTurn::content)
                .containsExactly("这一轮问题", "系统暂时繁忙，请稍后再试。");
    }

    /**
     * 直接最终回复为空时也必须走兜底路径，避免把空白正文直接透传给渠道层。
     */
    @Test
    void shouldReturnFallbackReplyWhenFinalReplyDecisionIsBlank() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new FinalReplyDecision("  "));
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-6"),
                new NormalizedDirectMessage("dev", "user-1", "dm-6", "msg-6", "这一轮问题"),
                new TraceContext("run-6", "dev", "dm-6", "msg-6", "conversation-6", RuntimeObservationMode.OFF)
        ));

        assertThat(replyEnvelope.body()).isEqualTo("系统暂时繁忙，请稍后再试。");
        assertThat(replyEnvelope.signals()).isEmpty();
    }

    /**
     * 统一构建启用 Tool System 的 AgentFacade，避免每个测试重复装配主链路依赖。
     */
    private DefaultAgentFacade createFacade(
            WorkspaceLoader workspaceLoader,
            InMemoryConversationTurnRepository turnRepository,
            AgentModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor
    ) {
        return createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, toolExecutor, new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF));
    }

    /**
     * 允许测试显式注入观测事件记录器，便于覆盖时间线与失败事件而不引入真实 sink 依赖。
     */
    private DefaultAgentFacade createFacade(
            WorkspaceLoader workspaceLoader,
            InMemoryConversationTurnRepository turnRepository,
            AgentModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            RuntimeObservationPublisher observationPublisher
    ) {
        return new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                new SkillResolver(new SkillMarkdownParser()),
                turnRepository,
                modelClient,
                toolRegistry,
                toolExecutor,
                new OpenClawProperties(
                        "workspace",
                        4,
                        "系统暂时繁忙，请稍后再试。",
                        new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                        new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                        new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160)
                ),
                observationPublisher
        );
    }

    /**
     * 构造包含显式 Skill 的 workspace 快照，用于验证规划阶段的上下文拼装顺序。
     */
    private WorkspaceSnapshot createWorkspaceSnapshotWithSkill() {
        return new WorkspaceSnapshot(
                List.of(
                        new WorkspaceFileContent("SOUL.md", "规则"),
                        new WorkspaceFileContent("SKILLS.md", "技能总览")
                ),
                List.of(
                        new WorkspaceFileContent("USER.md", "偏好"),
                        new WorkspaceFileContent("MEMORY.md", "记忆")
                ),
                List.of(new LocalSkillDocument(
                        "skills/code-review/SKILL.md",
                        """
                        ---
                        name: code-review
                        description: 审查风险
                        keywords:
                          - code-review
                        ---
                        先看风险，再给建议。
                        """
                ))
        );
    }

    /**
     * 构造不含 Skill 的 workspace 快照，用于覆盖工具闭环和兜底等非 Skill 路径。
     */
    private WorkspaceSnapshot createWorkspaceSnapshotWithoutSkill() {
        return new WorkspaceSnapshot(
                List.of(
                        new WorkspaceFileContent("SOUL.md", ""),
                        new WorkspaceFileContent("SKILLS.md", "")
                ),
                List.of(
                        new WorkspaceFileContent("USER.md", ""),
                        new WorkspaceFileContent("MEMORY.md", "")
                ),
                List.of()
        );
    }

    /**
     * 固定系统时间，确保 `time` 工具相关测试不受执行环境和时区差异影响。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-03T08:09:10Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 记录 Agent Core 发出的观测事件，便于测试断言时间线顺序、失败路径和 `runId` 关联语义。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 控制当前测试所模拟的观测模式，以便在无关用例中快速关闭事件记录。
         */
        private final RuntimeObservationMode mode;

        /**
         * 保存 Agent Core 发布的所有事件，供测试断言阶段顺序和字段边界。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 通过构造器显式声明模式，确保测试不依赖 Spring 配置绑定结果。
         */
        private RecordingRuntimeObservationPublisher(RuntimeObservationMode mode) {
            this.mode = mode;
        }

        /**
         * Agent Core 测试不会自己创建 trace，但这里仍实现接口以保持与生产契约一致。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext("generated-run", channel, externalConversationId, externalMessageId, null, mode);
        }

        /**
         * 记录只包含摘要字段的事件，满足时间线模式下的大多数断言需求。
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
         * 按原样保存事件对象，让测试能验证 `runId` 和阶段顺序，而不耦合控制台渲染格式。
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
