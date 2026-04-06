package com.quashy.openclaw4j.agent.model;

import java.util.List;

/**
 * 统一封装单聊主链路输出，正文与结构化信号分离，避免系统语义污染用户可见文本和后续检索语料。
 */
public record ReplyEnvelope(
        /**
         * 承载用户可直接看到的最终一次性回复正文，不混入结构化系统语义。
         */
        String body,
        /**
         * 承载对渠道层和后续扩展有意义的结构化信号，当前 change 默认可为空集合。
         */
        List<ReplySignal> signals
) {
}