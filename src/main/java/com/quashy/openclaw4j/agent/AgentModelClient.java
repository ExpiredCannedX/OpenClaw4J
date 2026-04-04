package com.quashy.openclaw4j.agent;

/**
 * 抽象 Agent Core 与具体模型 SDK 的边界，统一承载规划决策与最终回复两类模型交互。
 */
public interface AgentModelClient {

    /**
     * 根据规划阶段 prompt 返回结构化决策，使主链路能在“直接回复”和“单次工具调用”之间安全分支。
     */
    AgentModelDecision decideNextAction(AgentPrompt prompt);

    /**
     * 根据最终回复阶段 prompt 生成一次性回复正文，异常由调用侧决定是否降级为兜底回复。
     */
    String generateFinalReply(AgentPrompt prompt);
}
