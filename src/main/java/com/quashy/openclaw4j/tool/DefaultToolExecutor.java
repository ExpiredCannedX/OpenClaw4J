package com.quashy.openclaw4j.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 负责把同步工具执行收敛为统一结果，避免主链路直接处理工具缺失、参数错误和运行异常。
 */
@Component
public class DefaultToolExecutor implements ToolExecutor {

    /**
     * 提供工具目录与按名解析能力，使执行器可以独立于具体装配方式运行。
     */
    private final ToolRegistry toolRegistry;

    /**
     * 通过注册中心解析目标工具，把执行与目录发现职责解耦。
     */
    public DefaultToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 统一执行工具并把所有失败路径转换成结构化错误结果，保证调用侧永远不接收裸异常。
     */
    @Override
    public ToolExecutionResult execute(ToolCallRequest request) {
        return toolRegistry.findByName(request.toolName())
                .<ToolExecutionResult>map(tool -> executeResolvedTool(tool, request))
                .orElseGet(() -> new ToolExecutionError(
                        request.toolName(),
                        "tool_not_found",
                        "Requested tool is not registered.",
                        Map.of()
                ));
    }

    /**
     * 在工具已成功解析后执行实际调用，并把参数错误与未知异常映射为稳定错误码。
     */
    private ToolExecutionResult executeResolvedTool(Tool tool, ToolCallRequest request) {
        try {
            return new ToolExecutionSuccess(tool.definition().name(), tool.execute(request));
        } catch (ToolArgumentException exception) {
            return new ToolExecutionError(
                    tool.definition().name(),
                    "invalid_arguments",
                    exception.getMessage(),
                    exception.details()
            );
        } catch (Exception exception) {
            return new ToolExecutionError(
                    tool.definition().name(),
                    "execution_failed",
                    exception.getMessage() == null ? "Tool execution failed." : exception.getMessage(),
                    Map.of("exceptionType", exception.getClass().getSimpleName())
            );
        }
    }
}
