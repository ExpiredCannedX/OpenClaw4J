package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.AgentFacade;
import com.quashy.openclaw4j.agent.AgentRequest;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.store.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.store.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.store.memory.InMemoryProcessedMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证开发用单聊入口在进入 Agent 前，对身份映射、活跃会话和消息幂等的编排是否稳定。
 */
class DirectMessageServiceTest {

    /**
     * 同一渠道同一外部用户的后续消息必须复用首次创建的内部用户与活跃会话，避免多轮上下文被意外切断。
     */
    @Test
    void shouldReuseInternalUserAndConversationForSameChannelUser() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        when(agentFacade.reply(any())).thenReturn(new ReplyEnvelope("ok", List.of()));
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                agentFacade
        );

        service.handle(new DirectMessageRequest("dev", "user-1", "dm-1", "msg-1", "你好"));
        service.handle(new DirectMessageRequest("dev", "user-1", "dm-1", "msg-2", "继续"));

        ArgumentCaptor<AgentRequest> requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentFacade, times(2)).reply(requestCaptor.capture());
        List<AgentRequest> capturedRequests = requestCaptor.getAllValues();
        assertThat(capturedRequests.get(1).userId()).isEqualTo(capturedRequests.get(0).userId());
        assertThat(capturedRequests.get(1).conversationId()).isEqualTo(capturedRequests.get(0).conversationId());
    }

    /**
     * 相同外部消息被重复投递时必须直接返回首次结果，确保渠道重试不会再次触发模型调用。
     */
    @Test
    void shouldReturnExistingReplyForDuplicateMessage() {
        AgentFacade agentFacade = mock(AgentFacade.class);
        ReplyEnvelope replyEnvelope = new ReplyEnvelope("第一次回复", List.of());
        when(agentFacade.reply(any())).thenReturn(replyEnvelope);
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                agentFacade
        );

        ReplyEnvelope firstReply = service.handle(new DirectMessageRequest("dev", "user-1", "dm-1", "msg-1", "你好"));
        ReplyEnvelope secondReply = service.handle(new DirectMessageRequest("dev", "user-1", "dm-1", "msg-1", "你好"));

        assertThat(secondReply).isEqualTo(firstReply);
        verify(agentFacade, times(1)).reply(any());
    }
}
