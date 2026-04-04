package com.quashy.openclaw4j.agent.decision;

/**
 * 表示模型在规划阶段给出的结构化决策，使主链路无需解析脆弱的自由文本标签。
 */
public sealed interface AgentModelDecision permits FinalReplyDecision, ToolCallDecision {
}
