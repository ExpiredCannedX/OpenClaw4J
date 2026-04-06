package com.quashy.openclaw4j.tool.mcp;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示启动期从单个 MCP server discovery 到的工具定义，使目录构建与底层 SDK schema 类型解耦。
 */
public record McpDiscoveredTool(
        /**
         * 承载远端 MCP tool 的原始名称，后续会与 server alias 共同映射成内部唯一名。
         */
        String name,
        /**
         * 承载远端 MCP tool 的说明文字，供统一工具目录直接暴露给模型消费。
         */
        String description,
        /**
         * 承载远端 tool 的通用 JSON Schema 文档，要求在映射阶段尽量无损保留。
         */
        Map<String, Object> inputSchema
) {

    /**
     * 在 discovery 结果进入内部目录前校验最小元数据完整性，避免空名称或空 schema 污染注册边界。
     */
    public McpDiscoveredTool {
        Assert.hasText(name, "name must not be blank");
        Assert.hasText(description, "description must not be blank");
        inputSchema = Map.copyOf(Objects.requireNonNullElse(inputSchema, Map.of()));
    }
}
