package com.quashy.openclaw4j.observability.model;

/**
 * 承载单次运行的最小 trace 上下文，确保不同阶段发出的事件可以通过同一 `runId` 被可靠聚合。
 */
public record TraceContext(
        /**
         * 表示当前这次运行的全局关联标识，用于串起控制台时间线和未来多 sink 聚合。
         */
        String runId,
        /**
         * 标识本次运行所属的渠道来源，便于多渠道共享同一事件模型。
         */
        String channel,
        /**
         * 保存渠道原生会话标识，帮助开发者把事件回溯到外部会话上下文。
         */
        String externalConversationId,
        /**
         * 保存渠道原生消息标识，用于幂等与问题定位。
         */
        String externalMessageId,
        /**
         * 保存当前运行已知的内部会话标识，便于与 recent turns 和仓储状态关联。
         */
        String internalConversationId,
        /**
         * 记录当前运行采用的观测模式，让后续 sink 或测试可以直接理解当前信息边界。
         */
        RuntimeObservationMode mode
) {

    /**
     * 在内部会话标识可获得后返回带增强上下文的新实例，保持 trace 对象本身不可变。
     */
    public TraceContext withInternalConversationId(String internalConversationId) {
        return new TraceContext(runId, channel, externalConversationId, externalMessageId, internalConversationId, mode);
    }
}
