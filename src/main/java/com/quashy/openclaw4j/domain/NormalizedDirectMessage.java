package com.quashy.openclaw4j.domain;

/**
 * 表示经过开发入口标准化后的单聊消息，确保后续身份映射和 Agent Core 不再依赖 HTTP 请求结构。
 */
public record NormalizedDirectMessage(
        /**
         * 标识消息所属渠道，使同名外部 ID 在不同渠道间仍然可以隔离处理。
         */
        String channel,
        /**
         * 保留渠道原生用户 ID，便于后续审计和跨层排障。
         */
        String externalUserId,
        /**
         * 保留渠道原生会话 ID，为未来真实 adapter 迁移预留字段对齐空间。
         */
        String externalConversationId,
        /**
         * 保留渠道原生消息 ID，供幂等处理和问题追踪使用。
         */
        String externalMessageId,
        /**
         * 当前这条标准化单聊消息的正文内容。
         */
        String body
) {
}
