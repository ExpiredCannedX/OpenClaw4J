package com.quashy.openclaw4j.tool.mcp;

import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.model.ToolArgumentException;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionException;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把单个 discovery 到的 MCP tool 适配为内部 `Tool` 抽象，使 Agent Core 可以沿用统一注册和执行边界。
 */
public class McpBackedTool implements Tool {

    /**
     * 标识当前工具所属的 MCP server alias，便于错误收敛和运行期观测定位来源。
     */
    private final String serverAlias;

    /**
     * 标识远端原始 MCP tool 名称，实际调用时仍需使用该名称而不是内部前缀名。
     */
    private final String remoteToolName;

    /**
     * 承载暴露给注册中心和模型消费的统一工具定义，避免每次调用时重复构造目录元数据。
     */
    private final ToolDefinition definition;

    /**
     * 持有已初始化的 MCP session，使工具执行可以复用 discovery 阶段建立的连接。
     */
    private final McpClientSession session;

    /**
     * 负责在调用边界发布结构化运行事件，确保 MCP tool invocation 仍然进入统一观测链路。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 通过显式依赖注入固定内部名称、远端名称和会话边界，使单个 MCP tool 适配层职责保持单一。
     */
    public McpBackedTool(
            String serverAlias,
            McpDiscoveredTool discoveredTool,
            McpClientSession session,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.serverAlias = serverAlias;
        this.remoteToolName = discoveredTool.name();
        this.definition = new ToolDefinition(
                "mcp." + serverAlias + "." + discoveredTool.name(),
                discoveredTool.description(),
                ToolInputSchema.fromJsonSchema(discoveredTool.inputSchema())
        );
        this.session = session;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 返回统一目录定义，让 MCP tool 在 Agent Core 看起来与本地工具没有结构差异。
     */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * 在调用前做最小必填参数校验，再把请求转发给底层 MCP session 并发布结构化调用事件。
     */
    @Override
    public Map<String, Object> execute(ToolCallRequest request) {
        validateRequiredArguments(request.arguments());
        TraceContext traceContext = resolveTraceContext(request);
        runtimeObservationPublisher.emit(
                traceContext,
                "mcp.tool.invocation.started",
                RuntimeObservationPhase.TOOL,
                RuntimeObservationLevel.INFO,
                "McpBackedTool",
                Map.of(
                        "serverAlias", serverAlias,
                        "toolName", definition.name()
                )
        );
        try {
            Map<String, Object> payload = session.callTool(remoteToolName, request.arguments());
            runtimeObservationPublisher.emit(
                    traceContext,
                    "mcp.tool.invocation.completed",
                    RuntimeObservationPhase.TOOL,
                    RuntimeObservationLevel.INFO,
                    "McpBackedTool",
                    Map.of(
                            "serverAlias", serverAlias,
                            "toolName", definition.name()
                    ),
                    Map.of("payloadPreview", payload.toString())
            );
            return payload;
        } catch (ToolArgumentException | ToolExecutionException exception) {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "mcp.tool.invocation.failed",
                    RuntimeObservationPhase.TOOL,
                    RuntimeObservationLevel.WARN,
                    "McpBackedTool",
                    buildFailurePayload(exception),
                    Map.of("message", exception.getMessage())
            );
            throw exception;
        }
    }

    /**
     * 使用顶层 required 字段做最小参数校验，避免明显缺参请求先走一趟远端 transport 才失败。
     */
    private void validateRequiredArguments(Map<String, Object> arguments) {
        List<String> missingFields = definition.inputSchema().required().stream()
                .filter(field -> !arguments.containsKey(field) || arguments.get(field) == null)
                .toList();
        if (!missingFields.isEmpty()) {
            throw new ToolArgumentException(
                    "缺少必填参数: " + String.join(", ", missingFields),
                    Map.of("missingFields", missingFields)
            );
        }
    }

    /**
     * 优先复用当前请求已有 trace，避免 MCP tool 调用与 Agent 主链路在时间线中被拆成不同 run。
     */
    private TraceContext resolveTraceContext(ToolCallRequest request) {
        if (request.executionContext() != null && request.executionContext().traceContext() != null) {
            return request.executionContext().traceContext();
        }
        return runtimeObservationPublisher.createTrace("mcp", serverAlias, remoteToolName);
    }

    /**
     * 把不同类型的结构化失败统一转换成稳定 payload，便于时间线和测试按错误语义断言。
     */
    private Map<String, Object> buildFailurePayload(RuntimeException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serverAlias", serverAlias);
        payload.put("toolName", definition.name());
        if (exception instanceof ToolExecutionException toolExecutionException) {
            payload.put("errorCode", toolExecutionException.errorCode());
        } else {
            payload.put("errorCode", "invalid_arguments");
        }
        return Map.copyOf(payload);
    }
}
