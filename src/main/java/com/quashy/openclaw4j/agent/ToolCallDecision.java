package com.quashy.openclaw4j.agent;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示模型希望执行一次同步工具调用，再基于观察结果收敛为最终回复。
 */
public record ToolCallDecision(
        /**
         * 指向模型请求的唯一工具名称，供执行器后续解析具体工具实现。
         */
        String toolName,
        /**
         * 承载模型为本次工具调用生成的参数键值，供工具执行阶段直接消费。
         */
        Map<String, Object> arguments
) implements AgentModelDecision {

    /**
     * 在决策对象创建时冻结参数并校验工具名，避免主链路处理无效工具请求。
     */
    public ToolCallDecision {
        Assert.hasText(toolName, "toolName must not be blank");
        arguments = Map.copyOf(Objects.requireNonNullElse(arguments, Map.of()));
    }
}
