package com.quashy.openclaw4j.agent.model;

import java.util.Map;

/**
 * 表示附着在回复正文之外的结构化系统语义，为后续 Skill 和渠道降级渲染预留稳定协议。
 */
public record ReplySignal(
        /**
         * 标识该信号的语义类型，供渠道层选择渲染或降级策略。
         */
        String type,
        /**
         * 附带该信号的键值化上下文，当前阶段保持简单结构以便后续扩展。
         */
        Map<String, String> payload
) {
}