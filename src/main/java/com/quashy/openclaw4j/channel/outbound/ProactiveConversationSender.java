package com.quashy.openclaw4j.channel.outbound;

import com.quashy.openclaw4j.domain.InternalConversationId;

/**
 * 定义平台无关的主动文本回发抽象，使 Scheduler 只依赖内部会话语义而不直接感知具体渠道协议。
 */
public interface ProactiveConversationSender {

    /**
     * 向指定内部会话主动发送一条最终文本；若找不到目标或渠道发送失败，应抛出带稳定错误码的异常供上层决定重试。
     */
    void sendText(InternalConversationId conversationId, String text);
}
