package com.quashy.openclaw4j.agent.runtime;

import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import org.springframework.util.Assert;

/**
 * 表示当前请求内某一步执行后沉淀的结构化观察，使后续 planning 与 final-reply 阶段都能按稳定顺序回看历史。
 */
public record AgentObservation(
        /**
         * 标识产生该观察的编排 step 序号，使用从 1 开始的计数以便与运行时观测事件直接对齐。
         */
        int stepIndex,
        /**
         * 承载该 step 产生的工具成功或失败结果，供 prompt 组装、终止判定和测试断言复用。
         */
        ToolExecutionResult result
) {

    /**
     * 在创建观察时校验 step 序号和结果对象，避免无效历史污染后续规划上下文。
     */
    public AgentObservation {
        Assert.isTrue(stepIndex > 0, "stepIndex must be greater than 0");
        Assert.notNull(result, "result must not be null");
    }
}
