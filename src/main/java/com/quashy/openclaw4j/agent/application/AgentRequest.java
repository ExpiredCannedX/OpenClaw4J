package com.quashy.openclaw4j.agent.application;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.conversation.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.model.TraceContext;

/**
 * 表示统一 Agent 入口接收的内部请求，明确区分身份映射完成后的用户、会话与当前消息。
 */
public record AgentRequest(
        /**
         * 表示已经完成渠道身份映射后的内部用户标识，后续链路不再依赖外部用户 ID。
         */
        InternalUserId userId,
        /**
         * 表示当前消息所属的内部活跃会话，用于 recent turns 读取和回复落盘。
         */
        InternalConversationId conversationId,
        /**
         * 保存当前这次进入 Agent 的标准化单聊消息，是本轮推理的直接输入。
         */
        NormalizedDirectMessage message,
        /**
         * 保存当前这轮请求的 trace 上下文，使 Agent Core、工具和渠道出站事件都能共享同一 `runId`。
         */
        TraceContext traceContext
) {
}