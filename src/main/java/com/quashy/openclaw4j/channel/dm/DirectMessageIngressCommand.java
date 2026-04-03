package com.quashy.openclaw4j.channel.dm;

/**
 * 表示已经完成协议层翻译后的统一单聊入站命令，让开发入口和真实渠道 adapter 都能复用同一条应用服务边界。
 */
public record DirectMessageIngressCommand(
        /**
         * 标识消息来源渠道，用于隔离不同平台的身份映射与幂等键空间。
         */
        String channel,
        /**
         * 渠道原生用户标识，进入核心前仍保留为字符串以兼容不同平台的 ID 形态。
         */
        String externalUserId,
        /**
         * 渠道原生会话标识，用于让统一消息模型能够保留来源会话上下文。
         */
        String externalConversationId,
        /**
         * 渠道原生消息唯一标识，用于统一幂等去重。
         */
        String externalMessageId,
        /**
         * 经过渠道 adapter 提取后的用户可见文本正文，是进入 Agent 的最小消息内容。
         */
        String body
) {
}
