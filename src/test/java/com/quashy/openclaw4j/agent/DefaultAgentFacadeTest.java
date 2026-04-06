package com.quashy.openclaw4j.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashy.openclaw4j.agent.application.AgentRequest;
import com.quashy.openclaw4j.agent.decision.FinalReplyDecision;
import com.quashy.openclaw4j.agent.decision.ToolCallDecision;
import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.agent.prompt.AgentPromptAssembler;
import com.quashy.openclaw4j.agent.runtime.DefaultAgentFacade;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.conversation.ConversationTurn;
import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.conversation.NormalizedDirectMessage;
import com.quashy.openclaw4j.agent.model.ReplyEnvelope;
import com.quashy.openclaw4j.memory.LocalMemoryService;
import com.quashy.openclaw4j.memory.index.SqliteMemoryIndexer;
import com.quashy.openclaw4j.memory.store.MarkdownMemoryStore;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.skill.SkillMarkdownParser;
import com.quashy.openclaw4j.skill.SkillResolver;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryConversationTurnRepository;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import com.quashy.openclaw4j.tool.builtin.time.TimeTool;
import com.quashy.openclaw4j.tool.builtin.memory.MemoryRememberTool;
import com.quashy.openclaw4j.tool.builtin.memory.MemorySearchTool;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolExecutor;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionException;
import com.quashy.openclaw4j.tool.mcp.McpBackedTool;
import com.quashy.openclaw4j.tool.mcp.McpClientSession;
import com.quashy.openclaw4j.tool.mcp.McpDiscoveredTool;
import com.quashy.openclaw4j.tool.mcp.McpToolCatalog;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationService;
import com.quashy.openclaw4j.tool.safety.infrastructure.sqlite.SqliteToolSafetyRepository;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolRiskLevel;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyGuard;
import com.quashy.openclaw4j.tool.safety.validator.FilesystemWriteArgumentValidator;
import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                .contains("\"required\" : [ ]")
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
     * 当同一请求需要连续两个工具观察后才能回答时，系统必须在剩余预算内继续规划，并按顺序把历史观察回填到后续 planning/final-reply prompt。
     */
    @Test
    void shouldContinuePlanningWithOrderedObservationsUntilModelRequestsFinalReply() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any()))
                .thenReturn(new ToolCallDecision("first", Map.of()))
                .thenReturn(new ToolCallDecision("second", Map.of()))
                .thenReturn(new FinalReplyDecision("规划阶段已经足够"));
        when(modelClient.generateFinalReply(any())).thenReturn("这是整合两次工具观察后的最终回复。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(
                createSuccessTool("first", Map.of("step", "one")),
                createSuccessTool("second", Map.of("step", "two"))
        ));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-multi-step"),
                new NormalizedDirectMessage("dev", "user-1", "dm-multi-step", "msg-multi-step", "请串行完成两个步骤"),
                new TraceContext("run-multi-step", "dev", "dm-multi-step", "msg-multi-step", "conversation-multi-step", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> planningPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient, times(3)).decideNextAction(planningPromptCaptor.capture());
        assertThat(planningPromptCaptor.getAllValues()).hasSize(3);
        assertThat(planningPromptCaptor.getAllValues().get(0).content())
                .contains("【编排状态】")
                .contains("current_step: 1")
                .contains("【当前请求观察历史】")
                .contains("当前请求尚无结构化观察。");
        assertThat(planningPromptCaptor.getAllValues().get(1).content())
                .contains("current_step: 2")
                .contains("remaining_steps:")
                .contains("step: 1")
                .contains("tool_name: first")
                .contains("status: success")
                .contains("step=one");
        assertThat(planningPromptCaptor.getAllValues().get(2).content())
                .contains("current_step: 3")
                .contains("step: 1")
                .contains("tool_name: first")
                .contains("step: 2")
                .contains("tool_name: second")
                .contains("step=two");
        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("terminal_reason: final_reply")
                .contains("step: 1")
                .contains("tool_name: first")
                .contains("step: 2")
                .contains("tool_name: second");
        assertThat(finalPromptCaptor.getValue().content().indexOf("tool_name: first"))
                .isLessThan(finalPromptCaptor.getValue().content().indexOf("tool_name: second"));
        assertThat(replyEnvelope.body()).isEqualTo("这是整合两次工具观察后的最终回复。");
    }

    /**
     * 一次工具失败只要未命中硬终止边界，系统就必须把结构化错误带入下一轮规划，而不是立即结束当前请求。
     */
    @Test
    void shouldAllowReplanningAfterToolErrorWithinRemainingBudget() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any()))
                .thenReturn(new ToolCallDecision("broken", Map.of()))
                .thenReturn(new ToolCallDecision("fallback", Map.of()))
                .thenReturn(new FinalReplyDecision("规划完成"));
        when(modelClient.generateFinalReply(any())).thenReturn("已经在失败后完成了替代处理。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(
                createBrokenTool("broken"),
                createSuccessTool("fallback", Map.of("status", "ok"))
        ));
        DefaultAgentFacade facade = createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, new DefaultToolExecutor(toolRegistry));

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-replan-after-error"),
                new NormalizedDirectMessage("dev", "user-1", "dm-replan-after-error", "msg-replan-after-error", "先试第一个工具，失败后换一个"),
                new TraceContext("run-replan-after-error", "dev", "dm-replan-after-error", "msg-replan-after-error", "conversation-replan-after-error", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> planningPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient, times(3)).decideNextAction(planningPromptCaptor.capture());
        assertThat(planningPromptCaptor.getAllValues().get(1).content())
                .contains("tool_name: broken")
                .contains("status: error")
                .contains("error_code: execution_failed");
        assertThat(replyEnvelope.body()).isEqualTo("已经在失败后完成了替代处理。");
    }

    /**
     * 当请求在返回最终回复前已经耗尽 step budget 时，系统必须停止继续规划，并基于累计观察生成一次最终回复。
     */
    @Test
    void shouldStopLoopWhenStepBudgetIsExhaustedAndGenerateFinalReplyFromAccumulatedObservations() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any()))
                .thenReturn(new ToolCallDecision("first", Map.of()))
                .thenReturn(new ToolCallDecision("second", Map.of()))
                .thenReturn(new ToolCallDecision("third", Map.of()));
        when(modelClient.generateFinalReply(any())).thenReturn("预算已耗尽，我基于现有观察先给出结论。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(
                createSuccessTool("first", Map.of("step", "one")),
                createSuccessTool("second", Map.of("step", "two")),
                createSuccessTool("third", Map.of("step", "three"))
        ));
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                turnRepository,
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF),
                createProperties(Path.of("workspace"), 2)
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-budget-exhausted"),
                new NormalizedDirectMessage("dev", "user-1", "dm-budget-exhausted", "msg-budget-exhausted", "在两个 step 内尽量处理"),
                new TraceContext("run-budget-exhausted", "dev", "dm-budget-exhausted", "msg-budget-exhausted", "conversation-budget-exhausted", RuntimeObservationMode.OFF)
        ));

        verify(modelClient, times(2)).decideNextAction(any());
        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("terminal_reason: step_budget_exhausted")
                .contains("step: 1")
                .contains("tool_name: first")
                .contains("step: 2")
                .contains("tool_name: second")
                .doesNotContain("tool_name: third");
        assertThat(replyEnvelope.body()).isEqualTo("预算已耗尽，我基于现有观察先给出结论。");
    }

    /**
     * 当前消息命中显式确认且会话内存在待确认项时，系统必须短路恢复原始工具请求，而不是再让模型重规划一次。
     */
    @Test
    void shouldResumePendingToolRequestAfterExplicitConfirmation(@TempDir Path workspaceRoot) {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.generateFinalReply(any())).thenReturn("已按确认执行文件写入。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        AtomicInteger executionCount = new AtomicInteger();
        Tool guardedTool = createGuardedFilesystemWriteTool(executionCount);
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(guardedTool));
        ToolConfirmationService confirmationService = createToolConfirmationService(workspaceRoot);
        ToolExecutor toolExecutor = new DefaultToolExecutor(
                toolRegistry,
                new ToolPolicyGuard(
                        confirmationService,
                        new SqliteToolSafetyRepository(
                                workspaceRoot.resolve(".openclaw/tool-safety.sqlite"),
                                new ObjectMapper(),
                                fixedClock()
                        ),
                        new FilesystemWriteArgumentValidator(List.of("AGENTS.md", "SOUL.md", "SKILLS.md"))
                )
        );
        ToolCallRequest pendingRequest = new ToolCallRequest(
                "mcp.filesystem.write_file",
                Map.of(
                        "path", "notes/confirmed.md",
                        "content", "approved"
                ),
                new com.quashy.openclaw4j.tool.model.ToolExecutionContext(
                        new InternalUserId("user-1"),
                        new InternalConversationId("conversation-confirm"),
                        new NormalizedDirectMessage("dev", "user-1", "dm-confirm", "msg-pending", "请写文件"),
                        new TraceContext("run-pending", "dev", "dm-confirm", "msg-pending", "conversation-confirm", RuntimeObservationMode.OFF),
                        workspaceRoot
                )
        );
        confirmationService.createPendingConfirmation(
                pendingRequest,
                new ToolSafetyProfile(
                        ToolRiskLevel.DESTRUCTIVE,
                        ToolConfirmationPolicy.EXPLICIT,
                        ToolArgumentValidatorType.FILESYSTEM_WRITE
                ),
                "tool_confirmation_required"
        );
        DefaultAgentFacade facade = new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                new SkillResolver(new SkillMarkdownParser()),
                turnRepository,
                modelClient,
                toolRegistry,
                toolExecutor,
                createProperties(workspaceRoot, 1),
                publisher,
                confirmationService
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-confirm"),
                new NormalizedDirectMessage("dev", "user-1", "dm-confirm", "msg-confirm", "确认"),
                new TraceContext("run-confirm", "dev", "dm-confirm", "msg-confirm", "conversation-confirm", RuntimeObservationMode.OFF)
        ));

        verify(modelClient, never()).decideNextAction(any());
        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: mcp.filesystem.write_file")
                .contains("status: success");
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(replyEnvelope.body()).isEqualTo("已按确认执行文件写入。");
    }

    /**
     * 显式确认恢复真实工具执行后，只要预算仍有剩余，系统就必须继续进入后续规划，而不是立刻结束当前请求。
     */
    @Test
    void shouldResumePendingToolRequestAndContinuePlanningWhenBudgetRemains(@TempDir Path workspaceRoot) {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new FinalReplyDecision("规划阶段已完成"));
        when(modelClient.generateFinalReply(any())).thenReturn("确认后的工具执行已经完成，并继续完成了本轮处理。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        AtomicInteger executionCount = new AtomicInteger();
        Tool guardedTool = createGuardedFilesystemWriteTool(executionCount);
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(guardedTool));
        ToolConfirmationService confirmationService = createToolConfirmationService(workspaceRoot);
        ToolExecutor toolExecutor = new DefaultToolExecutor(
                toolRegistry,
                new ToolPolicyGuard(
                        confirmationService,
                        new SqliteToolSafetyRepository(
                                workspaceRoot.resolve(".openclaw/tool-safety.sqlite"),
                                new ObjectMapper(),
                                fixedClock()
                        ),
                        new FilesystemWriteArgumentValidator(List.of("AGENTS.md", "SOUL.md", "SKILLS.md"))
                )
        );
        ToolCallRequest pendingRequest = new ToolCallRequest(
                "mcp.filesystem.write_file",
                Map.of(
                        "path", "notes/confirmed-next.md",
                        "content", "approved"
                ),
                new com.quashy.openclaw4j.tool.model.ToolExecutionContext(
                        new InternalUserId("user-1"),
                        new InternalConversationId("conversation-confirm-loop"),
                        new NormalizedDirectMessage("dev", "user-1", "dm-confirm-loop", "msg-pending-loop", "请写文件"),
                        new TraceContext("run-pending-loop", "dev", "dm-confirm-loop", "msg-pending-loop", "conversation-confirm-loop", RuntimeObservationMode.OFF),
                        workspaceRoot
                )
        );
        confirmationService.createPendingConfirmation(
                pendingRequest,
                new ToolSafetyProfile(
                        ToolRiskLevel.DESTRUCTIVE,
                        ToolConfirmationPolicy.EXPLICIT,
                        ToolArgumentValidatorType.FILESYSTEM_WRITE
                ),
                "tool_confirmation_required"
        );
        DefaultAgentFacade facade = new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                new SkillResolver(new SkillMarkdownParser()),
                turnRepository,
                modelClient,
                toolRegistry,
                toolExecutor,
                createProperties(workspaceRoot),
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF),
                confirmationService
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-confirm-loop"),
                new NormalizedDirectMessage("dev", "user-1", "dm-confirm-loop", "msg-confirm-loop", "确认"),
                new TraceContext("run-confirm-loop", "dev", "dm-confirm-loop", "msg-confirm-loop", "conversation-confirm-loop", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> planningPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).decideNextAction(planningPromptCaptor.capture());
        assertThat(planningPromptCaptor.getValue().content())
                .contains("tool_name: mcp.filesystem.write_file")
                .contains("status: success");
        verify(modelClient).generateFinalReply(any());
        assertThat(executionCount.get()).isEqualTo(1);
        assertThat(replyEnvelope.body()).isEqualTo("确认后的工具执行已经完成，并继续完成了本轮处理。");
    }

    /**
     * 多步运行的观测事件必须带上 step 维度字段和终止原因，便于后续排障与预算治理。
     */
    @Test
    void shouldEmitStepScopedObservationPayloadsAndTerminalReasonForMultiStepRun() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any()))
                .thenReturn(new ToolCallDecision("first", Map.of()))
                .thenReturn(new ToolCallDecision("second", Map.of()))
                .thenReturn(new FinalReplyDecision("规划阶段已结束"));
        when(modelClient.generateFinalReply(any())).thenReturn("多步运行最终回复。");
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.TIMELINE);
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(
                createSuccessTool("first", Map.of("step", "one")),
                createSuccessTool("second", Map.of("step", "two"))
        ));
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher
        );

        facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-multi-step-events"),
                new NormalizedDirectMessage("dev", "user-1", "dm-multi-step-events", "msg-multi-step-events", "连续执行两个工具"),
                new TraceContext("run-multi-step-events", "dev", "dm-multi-step-events", "msg-multi-step-events", "conversation-multi-step-events", RuntimeObservationMode.TIMELINE)
        ));

        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .containsSubsequence(
                        "agent.model.decision.started",
                        "agent.model.decision.completed",
                        "agent.tool.execution.started",
                        "agent.tool.execution.completed",
                        "agent.model.decision.started",
                        "agent.model.decision.completed",
                        "agent.tool.execution.started",
                        "agent.tool.execution.completed",
                        "agent.orchestration.terminated",
                        "agent.model.final_reply.completed"
                );
        assertThat(publisher.events.stream()
                .filter(event -> event.eventType().equals("agent.model.decision.completed"))
                .map(event -> event.payload().get("stepIndex")))
                .containsExactly(1, 2, 3);
        RuntimeObservationEvent terminationEvent = publisher.events.stream()
                .filter(event -> event.eventType().equals("agent.orchestration.terminated"))
                .findFirst()
                .orElseThrow();
        assertThat(terminationEvent.payload())
                .containsEntry("terminalReason", "final_reply")
                .containsEntry("stepIndex", 3);
    }

    /**
     * 模型请求 MCP 工具时，系统必须沿用既有“一次工具调用 -> 结构化观察 -> 最终回复”闭环，而不是分叉出独立主链路。
     */
    @Test
    void shouldExecuteMcpToolAndIncludeStructuredObservationBeforeFinalReply() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision(
                "mcp.filesystem.read_file",
                Map.of("path", "README.md")
        ));
        when(modelClient.generateFinalReply(any())).thenReturn("我已经读取了 README。");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(createMcpBackedReadFileTool(
                new StubMcpClientSession(Map.of(
                        "content", List.of(Map.of("type", "text", "text", "README 内容")),
                        "isError", false
                )),
                publisher
        )));
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                turnRepository,
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-mcp-success"),
                new NormalizedDirectMessage("dev", "user-1", "dm-mcp-success", "msg-mcp-success", "帮我读一下 README"),
                new TraceContext("run-mcp-success", "dev", "dm-mcp-success", "msg-mcp-success", "conversation-mcp-success", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: mcp.filesystem.read_file")
                .contains("status: success")
                .contains("README 内容");
        assertThat(replyEnvelope.body()).isEqualTo("我已经读取了 README。");
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
     * optional MCP server 缺席时，规划阶段仍必须继续暴露剩余本地工具并完成最终回复，而不是因为增强能力缺席中断主链路。
     */
    @Test
    void shouldContinuePlanningWhenOptionalMcpServerIsAbsent() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new FinalReplyDecision("仍可继续处理。"));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        McpToolCatalog mcpToolCatalog = new McpToolCatalog(
                new OpenClawProperties.McpProperties(
                        Duration.ofSeconds(8),
                        Map.of("filesystem", new OpenClawProperties.McpServerProperties(
                                "cmd.exe",
                                List.of("/c", "npx"),
                                Map.of(),
                                "./workspace",
                                false
                        ))
                ),
                alias -> {
                    throw new IllegalStateException("optional server missing");
                },
                publisher
        );
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(TimeTool.forClock(fixedClock())));
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                new LocalToolRegistry(combineTools(List.of(TimeTool.forClock(fixedClock())), mcpToolCatalog.tools())),
                new DefaultToolExecutor(new LocalToolRegistry(combineTools(List.of(TimeTool.forClock(fixedClock())), mcpToolCatalog.tools()))),
                publisher
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-mcp-optional"),
                new NormalizedDirectMessage("dev", "user-1", "dm-mcp-optional", "msg-mcp-optional", "继续分析，不依赖外部工具"),
                new TraceContext("run-mcp-optional", "dev", "dm-mcp-optional", "msg-mcp-optional", "conversation-mcp-optional", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> promptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).decideNextAction(promptCaptor.capture());
        assertThat(promptCaptor.getValue().content())
                .contains("name: time")
                .doesNotContain("mcp.filesystem.read_file");
        assertThat(replyEnvelope.body()).isEqualTo("仍可继续处理。");
        assertThat(toolRegistry.listDefinitions())
                .extracting(ToolDefinition::name)
                .containsExactly("time");
    }

    /**
     * MCP 工具调用失败时，系统必须把 transport 失败转换成结构化观察，再继续走最终回复阶段而不是把异常抛给渠道层。
     */
    @Test
    void shouldConvertMcpToolFailureIntoStructuredObservation() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision(
                "mcp.filesystem.read_file",
                Map.of("path", "README.md")
        ));
        when(modelClient.generateFinalReply(any())).thenReturn("MCP 工具失败了，我先继续给你安全回复。");
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(createMcpBackedReadFileTool(
                new StubFailingMcpClientSession(new ToolExecutionException(
                        "transport_failure",
                        "filesystem disconnected",
                        Map.of("serverAlias", "filesystem")
                )),
                publisher
        )));
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-mcp-failure"),
                new NormalizedDirectMessage("dev", "user-1", "dm-mcp-failure", "msg-mcp-failure", "读文件失败也继续"),
                new TraceContext("run-mcp-failure", "dev", "dm-mcp-failure", "msg-mcp-failure", "conversation-mcp-failure", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: mcp.filesystem.read_file")
                .contains("status: error")
                .contains("error_code: transport_failure");
        assertThat(replyEnvelope.body()).isEqualTo("MCP 工具失败了，我先继续给你安全回复。");
    }

    /**
     * 模型请求 `memory.search` 时，系统必须返回结构化命中，并把相对路径、片段预览与正向 score 注入最终回复阶段。
     */
    @Test
    void shouldExecuteMemorySearchToolAndIncludeStructuredMatchesBeforeFinalReply(@TempDir Path workspaceRoot) throws Exception {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), """
                # 长期记忆

                - 用户喜欢黑咖啡
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: dev
                  - trigger_reason: user_confirmed
                """);
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("memory.search", Map.of(
                "query", "黑咖啡",
                "scope", "all"
        )));
        when(modelClient.generateFinalReply(any())).thenReturn("我找到了相关记忆。");
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        ToolRegistry toolRegistry = createMemoryToolRegistry(workspaceRoot, publisher);
        createMemoryIndexer(workspaceRoot).refreshChangedFiles();
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher,
                createProperties(workspaceRoot)
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-memory-search"),
                new NormalizedDirectMessage("dev", "user-1", "dm-memory-search", "msg-memory-search", "帮我回忆一下咖啡偏好"),
                new TraceContext("run-memory-search", "dev", "dm-memory-search", "msg-memory-search", "conversation-memory-search", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: memory.search")
                .contains("MEMORY.md")
                .contains("黑咖啡")
                .contains("score=");
        assertThat(replyEnvelope.body()).isEqualTo("我找到了相关记忆。");
    }

    /**
     * 模型请求 `memory.remember` 成功时，系统必须完成写入并把目标桶与相对路径元数据回填给最终回复阶段。
     */
    @Test
    void shouldExecuteMemoryRememberToolAndIncludeStructuredWriteMetadataBeforeFinalReply(@TempDir Path workspaceRoot) throws Exception {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("memory.remember", Map.of(
                "target", "long_term",
                "content", "用户每周五下午会安排复盘",
                "reason", "user_confirmed"
        )));
        when(modelClient.generateFinalReply(any())).thenReturn("我已经记住了。");
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        ToolRegistry toolRegistry = createMemoryToolRegistry(workspaceRoot, publisher);
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher,
                createProperties(workspaceRoot)
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-memory-remember"),
                new NormalizedDirectMessage("dev", "user-1", "dm-memory-remember", "msg-memory-remember", "请记住这个习惯"),
                new TraceContext("run-memory-remember", "dev", "dm-memory-remember", "msg-memory-remember", "conversation-memory-remember", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: memory.remember")
                .contains("relativePath=MEMORY.md")
                .contains("targetBucket=long_term");
        assertThat(Files.readString(workspaceRoot.resolve("MEMORY.md"))).contains("用户每周五下午会安排复盘");
        assertThat(replyEnvelope.body()).isEqualTo("我已经记住了。");
    }

    /**
     * memory 工具参数错误时，系统必须把 invalid_arguments 结果注入最终回复阶段，而不是直接中断主链路。
     */
    @Test
    void shouldConvertInvalidMemoryRememberArgumentsIntoStructuredObservation(@TempDir Path workspaceRoot) {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(createWorkspaceSnapshotWithoutSkill());
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.decideNextAction(any())).thenReturn(new ToolCallDecision("memory.remember", Map.of(
                "target", "user_profile",
                "category", "temporary_plan",
                "content", "下周写完路线图",
                "reason", "model_inferred"
        )));
        when(modelClient.generateFinalReply(any())).thenReturn("这条记忆不适合写入。");
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF);
        ToolRegistry toolRegistry = createMemoryToolRegistry(workspaceRoot, publisher);
        DefaultAgentFacade facade = createFacade(
                workspaceLoader,
                new InMemoryConversationTurnRepository(),
                modelClient,
                toolRegistry,
                new DefaultToolExecutor(toolRegistry),
                publisher,
                createProperties(workspaceRoot)
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-memory-invalid"),
                new NormalizedDirectMessage("dev", "user-1", "dm-memory-invalid", "msg-memory-invalid", "请记住一条临时计划"),
                new TraceContext("run-memory-invalid", "dev", "dm-memory-invalid", "msg-memory-invalid", "conversation-memory-invalid", RuntimeObservationMode.OFF)
        ));

        ArgumentCaptor<AgentPrompt> finalPromptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generateFinalReply(finalPromptCaptor.capture());
        assertThat(finalPromptCaptor.getValue().content())
                .contains("tool_name: memory.remember")
                .contains("status: error")
                .contains("error_code: invalid_arguments");
        assertThat(replyEnvelope.body()).isEqualTo("这条记忆不适合写入。");
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
        return createFacade(
                workspaceLoader,
                turnRepository,
                modelClient,
                toolRegistry,
                toolExecutor,
                new RecordingRuntimeObservationPublisher(RuntimeObservationMode.OFF),
                createProperties(Path.of("workspace"))
        );
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
        return createFacade(workspaceLoader, turnRepository, modelClient, toolRegistry, toolExecutor, observationPublisher, createProperties(Path.of("workspace")));
    }

    /**
     * 允许测试显式指定 workspace 根路径，便于 memory 工具直接操作临时目录中的真实文件与索引。
     */
    private DefaultAgentFacade createFacade(
            WorkspaceLoader workspaceLoader,
            InMemoryConversationTurnRepository turnRepository,
            AgentModelClient modelClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            RuntimeObservationPublisher observationPublisher,
            OpenClawProperties properties
    ) {
        return new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                new SkillResolver(new SkillMarkdownParser()),
                turnRepository,
                modelClient,
                toolRegistry,
                toolExecutor,
                properties,
                observationPublisher
        );
    }

    /**
     * 为 memory 集成测试构造真实工具目录，确保 Agent Core 经过 executor 走到文件写入和索引查询闭环。
     */
    private ToolRegistry createMemoryToolRegistry(Path workspaceRoot, RuntimeObservationPublisher observationPublisher) {
        LocalMemoryService memoryService = new LocalMemoryService(
                new MarkdownMemoryStore(workspaceRoot, fixedClock()),
                createMemoryIndexer(workspaceRoot),
                observationPublisher
        );
        return new LocalToolRegistry(List.of(
                new MemorySearchTool(memoryService),
                new MemoryRememberTool(memoryService)
        ));
    }

    /**
     * 为 memory 集成测试创建固定时钟的 SQLite indexer，确保写入时间和 session 文件命名可稳定断言。
     */
    private SqliteMemoryIndexer createMemoryIndexer(Path workspaceRoot) {
        return new SqliteMemoryIndexer(workspaceRoot, workspaceRoot.resolve(".openclaw/memory-index.sqlite"), fixedClock());
    }

    /**
     * 构造一个最小的 MCP read_file 工具，用于验证 Agent Core 能否无差别地执行并观察外部工具。
     */
    private Tool createMcpBackedReadFileTool(McpClientSession session, RuntimeObservationPublisher observationPublisher) {
        return new McpBackedTool(
                "filesystem",
                new McpDiscoveredTool(
                        "read_file",
                        "读取指定文件内容。",
                        ToolInputSchema.object(
                                Map.of("path", new ToolInputProperty("string", "需要读取的文件路径。")),
                                List.of("path")
                        ).schema()
                ),
                session,
                observationPublisher
        );
    }

    /**
     * 构造一个带显式确认策略的 filesystem 写工具，用于验证确认消息能否恢复真实执行。
     */
    private Tool createGuardedFilesystemWriteTool(AtomicInteger executionCount) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "mcp.filesystem.write_file",
                        "用于验证显式确认恢复执行的 filesystem 写工具。",
                        ToolInputSchema.object(
                                Map.of(
                                        "path", new ToolInputProperty("string", "目标路径。"),
                                        "content", new ToolInputProperty("string", "写入内容。")
                                ),
                                List.of("path", "content")
                        )
                );
            }

            @Override
            public ToolSafetyProfile safetyProfile() {
                return new ToolSafetyProfile(
                        ToolRiskLevel.DESTRUCTIVE,
                        ToolConfirmationPolicy.EXPLICIT,
                        ToolArgumentValidatorType.FILESYSTEM_WRITE
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                executionCount.incrementAndGet();
                return Map.of("written", true, "path", request.arguments().get("path"));
            }
        };
    }

    /**
     * 构造一个始终成功的最小工具，用于验证多步编排只关注观察历史与循环控制，不引入额外工具行为噪音。
     */
    private Tool createSuccessTool(String toolName, Map<String, Object> payload) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        toolName,
                        "用于测试多步编排成功路径的工具。",
                        ToolInputSchema.object(Map.of(), List.of())
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                return payload;
            }
        };
    }

    /**
     * 构造一个始终抛出异常的最小工具，用于验证错误观察能否进入下一轮规划而不是直接终止。
     */
    private Tool createBrokenTool(String toolName) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        toolName,
                        "用于测试多步编排失败路径的工具。",
                        ToolInputSchema.object(Map.of(), List.of())
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /**
     * 合并本地工具与 MCP 工具列表，避免集成测试为了目录组合逻辑重复手写样板代码。
     */
    private List<Tool> combineTools(List<Tool> localTools, List<Tool> mcpTools) {
        List<Tool> combinedTools = new ArrayList<>(localTools);
        combinedTools.addAll(mcpTools);
        return combinedTools;
    }

    /**
     * 为测试构造包含 memory 索引配置的集中配置对象，避免每个用例重复手写默认值。
     */
    private OpenClawProperties createProperties(Path workspaceRoot) {
        return createProperties(workspaceRoot, 4);
    }

    /**
     * 允许测试显式指定编排 step budget，从而覆盖“继续规划”和“预算耗尽即终止”两类循环边界。
     */
    private OpenClawProperties createProperties(Path workspaceRoot, int maxSteps) {
        return new OpenClawProperties(
                workspaceRoot.toString(),
                4,
                "系统暂时繁忙，请稍后再试。",
                new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                new OpenClawProperties.McpProperties(Duration.ofSeconds(20), Map.of()),
                new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160),
                new OpenClawProperties.OrchestrationProperties(maxSteps),
                new OpenClawProperties.ReminderProperties(".openclaw/reminders.sqlite"),
                new OpenClawProperties.SchedulerProperties(Duration.ofSeconds(15), 20, 3, Duration.ofMinutes(3)),
                new OpenClawProperties.MemoryProperties(".openclaw/memory-index.sqlite"),
                new OpenClawProperties.ToolSafetyProperties(null, null, null, null)
        );
    }

    /**
     * 为确认恢复执行测试创建真实确认流服务，保证显式确认消息走的就是生产语义而不是手工 stub。
     */
    private ToolConfirmationService createToolConfirmationService(Path workspaceRoot) {
        SqliteToolSafetyRepository repository = new SqliteToolSafetyRepository(
                workspaceRoot.resolve(".openclaw/tool-safety.sqlite"),
                new ObjectMapper(),
                fixedClock()
        );
        return new ToolConfirmationService(
                repository,
                repository,
                createProperties(workspaceRoot).toolSafety(),
                new ObjectMapper(),
                fixedClock()
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
     * 提供一个最小 MCP session 假实现，使 Agent Core 集成测试可以直接驱动成功路径而不依赖真实外部进程。
     */
    private static final class StubMcpClientSession implements McpClientSession {

        /**
         * 承载当前测试场景希望返回的标准化成功载荷，避免重复搭建 discovery 之外的复杂假数据结构。
         */
        private final Map<String, Object> payload;

        /**
         * 通过构造参数显式注入调用结果，使测试能聚焦 Agent Core 的工具闭环行为。
         */
        private StubMcpClientSession(Map<String, Object> payload) {
            this.payload = payload;
        }

        /**
         * 该测试不会在 session 内再次执行 discovery，因此这里返回空列表即可满足最小接口契约。
         */
        @Override
        public List<McpDiscoveredTool> listTools() {
            return List.of();
        }

        /**
         * 按原样返回预设 payload，模拟 MCP 工具成功调用后的结构化结果。
         */
        @Override
        public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
            return payload;
        }

        /**
         * 假 session 不持有真实资源，因此关闭操作保持空实现即可。
         */
        @Override
        public void close() {
        }
    }

    /**
     * 提供一个固定抛出结构化执行异常的 MCP session 假实现，用于验证 transport 失败的回填路径。
     */
    private static final class StubFailingMcpClientSession implements McpClientSession {

        /**
         * 承载当前测试场景希望暴露的结构化执行异常，避免把失败语义硬编码到工具适配层。
         */
        private final ToolExecutionException exception;

        /**
         * 通过构造参数注入异常对象，使测试可以精确声明 error code 与诊断细节。
         */
        private StubFailingMcpClientSession(ToolExecutionException exception) {
            this.exception = exception;
        }

        /**
         * 该测试只覆盖调用失败路径，因此 discovery 结果可直接返回空列表。
         */
        @Override
        public List<McpDiscoveredTool> listTools() {
            return List.of();
        }

        /**
         * 每次调用都抛出同一个结构化执行异常，模拟远端断连或 transport 失败场景。
         */
        @Override
        public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
            throw exception;
        }

        /**
         * 假 session 不持有真实资源，因此关闭操作保持空实现即可。
         */
        @Override
        public void close() {
        }
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
