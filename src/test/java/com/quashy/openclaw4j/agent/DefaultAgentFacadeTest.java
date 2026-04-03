package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.store.memory.InMemoryConversationTurnRepository;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceLoader;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证统一 Agent 入口在成功和失败路径上都能稳定完成上下文组装、模型调用和回复落盘。
 */
class DefaultAgentFacadeTest {

    /**
     * Agent 调用模型前必须把静态规则、动态记忆和最近会话一并组装进提示词，否则多轮单聊无法形成稳定语境。
     */
    @Test
    void shouldAssembleWorkspaceAndRecentTurnsIntoPrompt() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(new WorkspaceSnapshot(
                new WorkspaceFileContent("SOUL.md", "规则"),
                List.of(
                        new WorkspaceFileContent("USER.md", "偏好"),
                        new WorkspaceFileContent("MEMORY.md", "记忆")
                )
        ));
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.generate(any())).thenReturn("最终回复");
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        InternalConversationId conversationId = new InternalConversationId("conversation-1");
        turnRepository.appendTurn(conversationId, ConversationTurn.user("上一轮用户消息"));
        turnRepository.appendTurn(conversationId, ConversationTurn.assistant("上一轮助手消息"));
        DefaultAgentFacade facade = new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                turnRepository,
                modelClient,
                new OpenClawProperties(
                        "workspace",
                        4,
                        "兜底回复",
                        new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                        new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
                )
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                conversationId,
                new NormalizedDirectMessage("dev", "user-1", "dm-1", "msg-1", "这一轮问题")
        ));

        ArgumentCaptor<AgentPrompt> promptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(modelClient).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().content())
                .contains("规则")
                .contains("偏好")
                .contains("记忆")
                .contains("上一轮用户消息")
                .contains("上一轮助手消息")
                .contains("这一轮问题");
        assertThat(replyEnvelope.body()).isEqualTo("最终回复");
        assertThat(turnRepository.loadRecentTurns(conversationId, 10))
                .extracting(ConversationTurn::content)
                .containsExactly("上一轮用户消息", "上一轮助手消息", "这一轮问题", "最终回复");
    }

    /**
     * 模型调用异常时必须返回安全兜底回复，避免把底层异常直接暴露给渠道层。
     */
    @Test
    void shouldReturnFallbackReplyWhenModelInvocationFails() {
        WorkspaceLoader workspaceLoader = mock(WorkspaceLoader.class);
        when(workspaceLoader.load()).thenReturn(new WorkspaceSnapshot(
                new WorkspaceFileContent("SOUL.md", ""),
                List.of(
                        new WorkspaceFileContent("USER.md", ""),
                        new WorkspaceFileContent("MEMORY.md", "")
                )
        ));
        AgentModelClient modelClient = mock(AgentModelClient.class);
        when(modelClient.generate(any())).thenThrow(new IllegalStateException("boom"));
        InMemoryConversationTurnRepository turnRepository = new InMemoryConversationTurnRepository();
        DefaultAgentFacade facade = new DefaultAgentFacade(
                workspaceLoader,
                new AgentPromptAssembler(),
                turnRepository,
                modelClient,
                new OpenClawProperties(
                        "workspace",
                        4,
                        "系统暂时繁忙，请稍后再试。",
                        new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                        new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
                )
        );

        ReplyEnvelope replyEnvelope = facade.reply(new AgentRequest(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-1"),
                new NormalizedDirectMessage("dev", "user-1", "dm-1", "msg-1", "这一轮问题")
        ));

        assertThat(replyEnvelope.body()).isEqualTo("系统暂时繁忙，请稍后再试。");
        assertThat(replyEnvelope.signals()).isEmpty();
        assertThat(turnRepository.loadRecentTurns(new InternalConversationId("conversation-1"), 10))
                .extracting(ConversationTurn::content)
                .containsExactly("这一轮问题", "系统暂时繁忙，请稍后再试。");
    }
}
