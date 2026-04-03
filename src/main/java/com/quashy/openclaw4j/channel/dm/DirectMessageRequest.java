package com.quashy.openclaw4j.channel.dm;

/**
 * 表示开发用 direct-message HTTP 入口的请求体，字段命名贴近未来渠道 adapter 需要的最小识别信息。
 */
public record DirectMessageRequest(
        /**
         * 标识消息来自哪个开发或真实渠道，用于身份映射和幂等键隔离。
         */
        String channel,
        /**
         * 渠道原生用户 ID，在当前 change 中用于映射内部用户与活跃会话。
         */
        String externalUserId,
        /**
         * 渠道原生单聊会话 ID，当前主要作为标准化消息的一部分保留下来。
         */
        String externalConversationId,
        /**
         * 渠道原生消息唯一 ID，是幂等去重的直接键。
         */
        String externalMessageId,
        /**
         * 用户提交的消息正文，会在标准化后进入 Agent 核心。
         */
        String body
) {
}
