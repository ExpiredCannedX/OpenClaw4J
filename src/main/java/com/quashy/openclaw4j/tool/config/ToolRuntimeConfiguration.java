package com.quashy.openclaw4j.tool.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolExecutor;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.mcp.McpClientFactory;
import com.quashy.openclaw4j.tool.mcp.McpToolCatalog;
import com.quashy.openclaw4j.tool.mcp.SpringMcpClientFactory;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationService;
import com.quashy.openclaw4j.tool.safety.infrastructure.sqlite.SqliteToolSafetyRepository;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyGuard;
import com.quashy.openclaw4j.tool.safety.validator.FilesystemWriteArgumentValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
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

    /**
     * 创建工具安全治理的本地 SQLite 仓储，使待确认状态和审计日志拥有统一事实源。
     */
    @Bean
    public SqliteToolSafetyRepository sqliteToolSafetyRepository(
            OpenClawProperties properties,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        return new SqliteToolSafetyRepository(
                resolveSafetyDatabaseFile(properties),
                objectMapper,
                applicationClock
        );
    }

    /**
     * 创建确认流服务，使显式确认消息能够只依赖服务端持久化事实恢复执行原始工具请求。
     */
    @Bean
    public ToolConfirmationService toolConfirmationService(
            SqliteToolSafetyRepository sqliteToolSafetyRepository,
            OpenClawProperties properties,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        return new ToolConfirmationService(
                sqliteToolSafetyRepository,
                sqliteToolSafetyRepository,
                properties.toolSafety(),
                objectMapper,
                applicationClock
        );
    }

    /**
     * 创建统一策略层，使所有真实工具执行前都能共享同一确认流和 filesystem 写参数校验边界。
     */
    @Bean
    public ToolPolicyGuard toolPolicyGuard(
            ToolConfirmationService toolConfirmationService,
            SqliteToolSafetyRepository sqliteToolSafetyRepository,
            OpenClawProperties properties
    ) {
        return new ToolPolicyGuard(
                toolConfirmationService,
                sqliteToolSafetyRepository,
                new FilesystemWriteArgumentValidator(properties.toolSafety().sensitivePaths())
        );
    }

    /**
     * 创建接入统一策略层的同步执行器，确保本地工具和 MCP 工具都必须先经过服务端判定。
     */
    @Bean
    public ToolExecutor toolExecutor(ToolRegistry toolRegistry, ToolPolicyGuard toolPolicyGuard) {
        return new DefaultToolExecutor(toolRegistry, toolPolicyGuard);
    }

    /**
     * 根据集中配置解析工具安全治理 SQLite 文件路径，并在相对路径场景下以 workspace 根目录为唯一基准。
     */
    private Path resolveSafetyDatabaseFile(OpenClawProperties properties) {
        Path configuredPath = Path.of(properties.toolSafety().databaseFile());
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        return Path.of(properties.workspaceRoot()).resolve(configuredPath).normalize();
    }
}
