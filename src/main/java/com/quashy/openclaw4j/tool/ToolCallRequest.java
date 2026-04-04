package com.quashy.openclaw4j.tool;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示一次标准化工具调用请求，统一承载目标工具名与解析后的参数载荷。
 */
public record ToolCallRequest(
        /**
         * 指向本次要执行的唯一工具名称，用于注册中心解析具体实现。
         */
        String toolName,
        /**
         * 承载已经过模型决策阶段解析的参数键值，供工具执行时读取。
         */
        Map<String, Object> arguments
) {

    /**
     * 在请求创建时冻结参数并校验工具名，避免执行阶段收到不完整的调用请求。
     */
    public ToolCallRequest {
        Assert.hasText(toolName, "toolName must not be blank");
        arguments = Map.copyOf(Objects.requireNonNullElse(arguments, Map.of()));
    }
}
