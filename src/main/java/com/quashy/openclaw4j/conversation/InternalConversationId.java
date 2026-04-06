package com.quashy.openclaw4j.conversation;

/**
 * 标识平台无关的内部会话身份，用于隔离渠道层的会话模型与 Agent Core 的多轮上下文边界。
 */
public record InternalConversationId(
        /**
         * 承载内部会话唯一值，具体生成策略由仓储实现决定，上层只依赖其稳定性。
         */
        String value
) {
}