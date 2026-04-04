package com.quashy.openclaw4j.tool.model;

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
        Map<String, Object> arguments,
        /**
         * 承载由系统填充的执行上下文，使工具可以读取用户、会话、消息来源和 workspace 等运行时事实。
         */
        ToolExecutionContext executionContext
) {

    /**
     * 为不依赖运行时上下文的已有工具保留最小构造入口，避免所有旧调用点都被迫立即传递空上下文。
     */
    public ToolCallRequest(String toolName, Map<String, Object> arguments) {
        this(toolName, arguments, null);
    }

    /**
     * 在请求创建时冻结参数并校验工具名，避免执行阶段收到不完整的调用请求。
     */
    public ToolCallRequest {
        Assert.hasText(toolName, "toolName must not be blank");
        arguments = Map.copyOf(Objects.requireNonNullElse(arguments, Map.of()));
    }
}
