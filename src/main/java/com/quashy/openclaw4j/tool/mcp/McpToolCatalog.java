package com.quashy.openclaw4j.tool.mcp;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责在启动期初始化已配置的 MCP server、执行 tool discovery，并暴露统一 MCP 工具目录。
 */
public class McpToolCatalog implements AutoCloseable {

    /**
     * 保存当前进程内 discovery 成功的 MCP 工具列表，供统一注册中心按需组合到总目录。
     */
    private final List<Tool> tools;

    /**
     * 保存成功初始化的底层 session，便于应用关闭时统一释放 transport 与子进程资源。
     */
    private final Map<String, McpClientSession> sessions;

    /**
     * 通过构造阶段完成 MCP 初始化和 discovery，使 required server 失败能够自然中断应用启动。
     */
    public McpToolCatalog(
            OpenClawProperties.McpProperties properties,
            McpClientFactory clientFactory,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        List<Tool> collectedTools = new ArrayList<>();
        Map<String, McpClientSession> collectedSessions = new LinkedHashMap<>();
        properties.servers().forEach((serverAlias, serverProperties) -> initializeServer(
                serverAlias,
                serverProperties,
                clientFactory,
                runtimeObservationPublisher,
                collectedTools,
                collectedSessions
        ));
        this.tools = List.copyOf(collectedTools);
        this.sessions = Map.copyOf(collectedSessions);
    }

    /**
     * 返回 discovery 成功后映射得到的 MCP 工具目录快照，供统一注册中心与测试直接消费。
     */
    public List<Tool> tools() {
        return tools;
    }

    /**
     * 在应用关闭时释放所有已初始化的 session，避免外部 MCP 子进程或连接泄漏。
     */
    @Override
    public void close() {
        sessions.values().forEach(McpClientSession::close);
    }

    /**
     * 对单个 server 执行初始化与 discovery，并根据 required/optional 策略决定 fail-fast 还是降级。
     */
    private void initializeServer(
            String serverAlias,
            OpenClawProperties.McpServerProperties serverProperties,
            McpClientFactory clientFactory,
            RuntimeObservationPublisher runtimeObservationPublisher,
            List<Tool> collectedTools,
            Map<String, McpClientSession> collectedSessions
    ) {
        TraceContext traceContext = runtimeObservationPublisher.createTrace("mcp", serverAlias, "startup");
        runtimeObservationPublisher.emit(
                traceContext,
                "mcp.server.initialization.started",
                RuntimeObservationPhase.TOOL,
                RuntimeObservationLevel.INFO,
                "McpToolCatalog",
                Map.of(
                        "serverAlias", serverAlias,
                        "required", serverProperties.required()
                )
        );
        McpClientSession session = null;
        String failureStage = "initialization";
        try {
            session = clientFactory.create(serverAlias);
            runtimeObservationPublisher.emit(
                    traceContext,
                    "mcp.server.initialization.completed",
                    RuntimeObservationPhase.TOOL,
                    RuntimeObservationLevel.INFO,
                    "McpToolCatalog",
                    Map.of("serverAlias", serverAlias)
            );
            failureStage = "discovery";
            List<McpDiscoveredTool> discoveredTools = session.listTools();
            McpClientSession activeSession = session;
            collectedSessions.put(serverAlias, session);
            discoveredTools.stream()
                    .map(discoveredTool -> new McpBackedTool(serverAlias, discoveredTool, activeSession, runtimeObservationPublisher))
                    .forEach(collectedTools::add);
            runtimeObservationPublisher.emit(
                    traceContext,
                    "mcp.tool.discovery.completed",
                    RuntimeObservationPhase.TOOL,
                    RuntimeObservationLevel.INFO,
                    "McpToolCatalog",
                    Map.of(
                            "serverAlias", serverAlias,
                            "toolCount", discoveredTools.size()
                    )
            );
        } catch (RuntimeException exception) {
            closeQuietly(session);
            if (serverProperties.required()) {
                throw new IllegalStateException("Failed to initialize required MCP server: " + serverAlias, exception);
            }
            runtimeObservationPublisher.emit(
                    traceContext,
                    "mcp.server.initialization.degraded",
                    RuntimeObservationPhase.TOOL,
                    RuntimeObservationLevel.WARN,
                    "McpToolCatalog",
                    Map.of(
                            "serverAlias", serverAlias,
                            "stage", failureStage
                    ),
                    Map.of("message", exception.getMessage() == null ? "" : exception.getMessage())
            );
        }
    }

    /**
     * 在初始化或 discovery 失败后安全关闭已创建的 session，避免 optional 降级时遗留半初始化资源。
     */
    private void closeQuietly(McpClientSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
