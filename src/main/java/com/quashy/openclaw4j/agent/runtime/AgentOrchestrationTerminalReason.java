package com.quashy.openclaw4j.agent.runtime;

import java.util.Locale;

/**
 * 定义单次请求内有界编排循环的终止原因，使 prompt 与运行期观测能够复用同一组稳定语义。
 */
public enum AgentOrchestrationTerminalReason {

    /**
     * 表示规划阶段已经给出最终回答意图，本轮不再继续新增工具步骤。
     */
    FINAL_REPLY,
    /**
     * 表示当前步骤命中了需要显式确认的安全边界，本轮必须暂停等待用户下一条消息。
     */
    CONFIRMATION_REQUIRED,
    /**
     * 表示当前请求已经耗尽允许的 step budget，系统必须收敛为一次最终回复。
     */
    STEP_BUDGET_EXHAUSTED;

    /**
     * 返回供 prompt 与事件载荷复用的稳定小写编码，避免调用方自行拼接字符串导致语义漂移。
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
