package com.quashy.openclaw4j.tool.schema;

import org.springframework.util.Assert;

import java.util.Objects;

/**
 * 表示暴露给模型消费的标准化工具定义，隔离内部实现与外部目录展示所需的稳定元数据。
 */
public record ToolDefinition(
        /**
         * 承载工具的全局唯一名称，供模型规划和注册中心解析时使用。
         */
        String name,
        /**
         * 描述工具用途与适用场景，帮助模型判断是否需要请求该工具。
         */
        String description,
        /**
         * 描述工具输入参数结构，确保模型可见的参数约束独立于具体 SDK。
         */
        ToolInputSchema inputSchema
) {

    /**
     * 在定义创建时校验最小元数据完整性，避免空名称或空 schema 进入工具目录。
     */
    public ToolDefinition {
        Assert.hasText(name, "tool name must not be blank");
        Assert.hasText(description, "tool description must not be blank");
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    }
}
