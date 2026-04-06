package com.quashy.openclaw4j.agent.runtime;

import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 表示单次请求内的编排状态，负责统一收敛 step budget、按序观察历史和终止原因等 request-local 事实。
 */
public record AgentOrchestrationState(
        /**
         * 控制当前请求最多允许沉淀多少个工具观察，避免多步同步闭环无限扩张。
         */
        int maxSteps,
        /**
         * 按执行顺序保存当前请求已产生的结构化观察，供后续 planning 和最终回复阶段复用。
         */
        List<AgentObservation> observationHistory,
        /**
         * 标识当前编排是否已经命中硬终止边界；为空表示仍可继续规划下一步。
         */
        AgentOrchestrationTerminalReason terminalReason
) {

    /**
     * 为调用方提供只声明最大 budget 的最小构造入口，使新请求可以从空历史状态启动。
     */
    public AgentOrchestrationState(int maxSteps) {
        this(maxSteps, List.of(), null);
    }

    /**
     * 在状态创建时冻结观察历史并校验 budget，保证循环控制逻辑永远建立在稳定、不可变的数据之上。
     */
    public AgentOrchestrationState {
        Assert.isTrue(maxSteps > 0, "maxSteps must be greater than 0");
        observationHistory = List.copyOf(Objects.requireNonNullElse(observationHistory, List.of()));
    }

    /**
     * 返回下一轮 planning 应使用的 step 序号，让 prompt 与观测事件始终对齐同一计数口径。
     */
    public int currentStepIndex() {
        return observationHistory.size() + 1;
    }

    /**
     * 返回当前请求还可消耗的剩余 step 数量，供循环边界和 prompt budget 信息复用。
     */
    public int remainingSteps() {
        return Math.max(maxSteps - observationHistory.size(), 0);
    }

    /**
     * 判断当前请求是否已经产生过至少一个结构化观察，便于区分直接回复与“先观察后收敛”的路径。
     */
    public boolean hasObservationHistory() {
        return !observationHistory.isEmpty();
    }

    /**
     * 判断当前状态是否已经命中硬终止边界，供主循环快速决定是否继续规划下一步。
     */
    public boolean isTerminal() {
        return terminalReason != null;
    }

    /**
     * 追加一个新观察并推进下一轮 planning step 序号，保持观察顺序与执行顺序完全一致。
     */
    public AgentOrchestrationState appendObservation(ToolExecutionResult result) {
        List<AgentObservation> updatedHistory = new ArrayList<>(observationHistory);
        updatedHistory.add(new AgentObservation(currentStepIndex(), result));
        return new AgentOrchestrationState(maxSteps, updatedHistory, terminalReason);
    }

    /**
     * 返回绑定了终止原因的新状态副本，使循环控制与 prompt/observability 使用同一份终态事实。
     */
    public AgentOrchestrationState withTerminalReason(AgentOrchestrationTerminalReason terminalReason) {
        Assert.notNull(terminalReason, "terminalReason must not be null");
        return new AgentOrchestrationState(maxSteps, observationHistory, terminalReason);
    }
}
