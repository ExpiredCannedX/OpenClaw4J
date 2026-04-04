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
                new NormalizedDirectMessage("dev", "user-1", "dm-1", "msg-1", "请使用 $code-review 处理这一轮问题")
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
                new NormalizedDirectMessage("dev", "user-1", "dm-2", "msg-2", "现在几点了？")
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
                new NormalizedDirectMessage("dev", "user-1", "dm-3", "msg-3", "帮我调用一个还没接入的工具")
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
                new NormalizedDirectMessage("dev", "user-1", "dm-4", "msg-4", "请尝试坏掉的工具")
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
                new NormalizedDirectMessage("dev", "user-1", "dm-5", "msg-5", "这一轮问题")
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
                new NormalizedDirectMessage("dev", "user-1", "dm-6", "msg-6", "这一轮问题")
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
                        new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
                )
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
}
