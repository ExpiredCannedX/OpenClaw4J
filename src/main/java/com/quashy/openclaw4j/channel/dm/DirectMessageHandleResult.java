package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.domain.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.TraceContext;

/**
 * 表示一次统一单聊 ingress 处理的结果，明确区分“新处理完成”与“重复投递复用旧结果”两种来源。
 */
public record DirectMessageHandleResult(
        /**
         * 承载最终返回给调用方的统一回复结构，无论是新生成还是命中幂等缓存都复用同一模型。
         */
        ReplyEnvelope replyEnvelope,
        /**
         * 标识这次结果是否来自首次成功处理；为 `false` 时表示命中了进行中或已完成的重复投递。
         */
        boolean newlyProcessed,
        /**
         * 保存当前处理链路的 trace 上下文，便于 Telegram 等渠道继续沿同一 `runId` 发出出站事件。
         */
        TraceContext traceContext
) {
}
