package com.quashy.openclaw4j.agent.decision;

import java.util.Objects;

/**
 * 表示模型已经具备足够信息，可以直接生成最终一次性回复而无需工具调用。
 */
public record FinalReplyDecision(
        /**
         * 承载模型生成的最终回复正文，调用侧会在空白时统一回退到兜底回复。
         */
        String reply
) implements AgentModelDecision {

    /**
     * 在决策对象创建时保证回复字段存在，避免主链路处理空引用分支。
     */
    public FinalReplyDecision {
        Objects.requireNonNull(reply, "reply must not be null");
    }
}
