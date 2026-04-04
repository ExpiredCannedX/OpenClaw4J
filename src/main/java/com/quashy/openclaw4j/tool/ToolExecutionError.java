package com.quashy.openclaw4j.tool;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示工具执行失败后的结构化错误结果，使主链路可以把失败作为观察继续收敛而不是直接中断。
 */
public record ToolExecutionError(
        /**
         * 标识发生失败的工具名，便于定位错误来源。
         */
        String toolName,
        /**
         * 承载稳定错误码，供 prompt 和测试区分工具不可用、参数错误及运行异常。
         */
        String errorCode,
        /**
         * 解释本次失败的直接原因，帮助最终回复阶段生成可理解的安全答复。
         */
        String message,
        /**
         * 携带补充错误细节，例如缺失字段名或异常类型，便于后续扩展诊断能力。
         */
        Map<String, Object> details
) implements ToolExecutionResult {

    /**
     * 在错误结果创建时固定错误元数据，确保所有失败路径都以统一结构暴露给上层。
     */
    public ToolExecutionError {
        Assert.hasText(toolName, "toolName must not be blank");
        Assert.hasText(errorCode, "errorCode must not be blank");
        Assert.hasText(message, "message must not be blank");
        details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }
}
