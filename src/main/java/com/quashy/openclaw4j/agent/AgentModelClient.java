package com.quashy.openclaw4j.agent;

/**
 * 抽象 Agent Core 与具体模型 SDK 的边界，便于测试替身和后续更换模型实现时保持主链路稳定。
 */
public interface AgentModelClient {

    /**
     * 根据统一提示词生成最终一次性回复文本，异常由调用侧决定是否降级为兜底回复。
     */
    String generate(AgentPrompt prompt);
}
