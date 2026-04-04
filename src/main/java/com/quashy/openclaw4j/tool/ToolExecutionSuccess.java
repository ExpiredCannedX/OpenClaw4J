package com.quashy.openclaw4j.tool;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示工具成功执行后的结构化结果，让 Agent Core 能稳定回填机器可读观察数据。
 */
public record ToolExecutionSuccess(
        /**
         * 标识产生当前成功结果的工具名，便于跟踪观察来源。
         */
        String toolName,
        /**
         * 承载工具归一化后的输出载荷，供最终回复阶段引用。
         */
        Map<String, Object> payload
) implements ToolExecutionResult {

    /**
     * 在成功结果创建时校验工具名并冻结载荷，避免观察结果被后续流程意外篡改。
     */
    public ToolExecutionSuccess {
        Assert.hasText(toolName, "toolName must not be blank");
        payload = Map.copyOf(Objects.requireNonNullElse(payload, Map.of()));
    }
}
