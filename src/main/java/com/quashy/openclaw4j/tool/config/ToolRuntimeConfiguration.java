package com.quashy.openclaw4j.tool.config;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.mcp.McpClientFactory;
import com.quashy.openclaw4j.tool.mcp.McpToolCatalog;
import com.quashy.openclaw4j.tool.mcp.SpringMcpClientFactory;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一装配本地工具与 MCP 工具目录，确保 Agent Core 始终通过单一 `ToolRegistry` 消费组合后的工具边界。
 */
@Configuration
public class ToolRuntimeConfiguration {

    /**
     * 使用 Spring MCP SDK 创建默认 client 工厂，把底层 `stdio` transport 细节收敛在工具配置层。
     */
    @Bean
    public McpClientFactory mcpClientFactory(OpenClawProperties properties) {
        return new SpringMcpClientFactory(properties.mcp());
    }

    /**
     * 在应用启动期初始化 MCP server 与工具目录，并在关闭时统一释放底层 session 资源。
     */
    @Bean(destroyMethod = "close")
    public McpToolCatalog mcpToolCatalog(
            OpenClawProperties properties,
            McpClientFactory mcpClientFactory,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        return new McpToolCatalog(properties.mcp(), mcpClientFactory, runtimeObservationPublisher);
    }

    /**
     * 把本地工具与 MCP 工具目录组合成单一注册中心，保证名称解析、重复校验和 prompt 暴露都共享同一边界。
     */
    @Bean
    public ToolRegistry toolRegistry(List<Tool> localTools, McpToolCatalog mcpToolCatalog) {
        List<Tool> combinedTools = new ArrayList<>(localTools);
        combinedTools.addAll(mcpToolCatalog.tools());
        return new LocalToolRegistry(combinedTools);
    }
}
