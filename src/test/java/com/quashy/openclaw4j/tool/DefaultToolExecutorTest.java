package com.quashy.openclaw4j.tool;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.model.*;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证同步 ToolExecutor 会把成功执行、参数错误和运行时异常统一收敛为结构化结果。
 */
class DefaultToolExecutorTest {

    /**
     * 工具执行成功时必须返回结构化成功结果，便于 Agent Core 统一回填观察结果。
     */
    @Test
    void shouldReturnStructuredSuccessWhenToolExecutesSuccessfully() {
        Tool echoTool = createEchoTool();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(echoTool));
        ToolExecutionResult result = new DefaultToolExecutor(toolRegistry).execute(new ToolCallRequest("echo", Map.of("text", "hello")));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionSuccess.class, success -> {
                    assertThat(success.toolName()).isEqualTo("echo");
                    assertThat(success.payload()).containsEntry("echo", "hello");
                });
    }

    /**
     * 参数校验失败时必须返回结构化错误，而不是把工具实现抛出的异常直接泄露出去。
     */
    @Test
    void shouldReturnStructuredInvalidArgumentsWhenToolRejectsArguments() {
        Tool echoTool = createEchoTool();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(echoTool));
        ToolExecutionResult result = new DefaultToolExecutor(toolRegistry).execute(new ToolCallRequest("echo", Map.of()));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.toolName()).isEqualTo("echo");
                    assertThat(error.errorCode()).isEqualTo("invalid_arguments");
                    assertThat(error.message()).contains("text");
                    assertThat(error.details()).containsEntry("field", "text");
                });
    }

    /**
     * 工具内部抛出运行时异常时必须被统一收敛为结构化错误结果，保证主链路可预测。
     */
    @Test
    void shouldReturnStructuredExecutionErrorWhenToolThrowsUnexpectedException() {
        Tool brokenTool = createBrokenTool();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(brokenTool));
        ToolExecutionResult result = new DefaultToolExecutor(toolRegistry).execute(new ToolCallRequest("broken", Map.of("mode", "now")));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.toolName()).isEqualTo("broken");
                    assertThat(error.errorCode()).isEqualTo("execution_failed");
                    assertThat(error.message()).contains("boom");
                });
    }

    /**
     * 当工具显式抛出结构化执行错误时，执行器必须保留该错误码与细节，而不是再次折叠成泛化的 execution_failed。
     */
    @Test
    void shouldPreserveStructuredExecutionErrorWhenToolThrowsToolExecutionException() {
        Tool timeoutTool = createTimeoutTool();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(timeoutTool));
        ToolExecutionResult result = new DefaultToolExecutor(toolRegistry).execute(new ToolCallRequest("mcp.filesystem.read_file", Map.of("path", "/tmp/a.txt")));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.toolName()).isEqualTo("mcp.filesystem.read_file");
                    assertThat(error.errorCode()).isEqualTo("timeout");
                    assertThat(error.message()).contains("timed out");
                    assertThat(error.details()).containsEntry("serverAlias", "filesystem");
                });
    }

    /**
     * 执行器在转发调用时必须保留运行时上下文，避免后续 memory 等工具拿不到用户、会话和渠道来源。
     */
    @Test
    void shouldPassExecutionContextThroughToToolImplementation() {
        Tool captureContextTool = createCaptureContextTool();
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(captureContextTool));
        ToolExecutionContext executionContext = new ToolExecutionContext(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-1"),
                new NormalizedDirectMessage("telegram", "external-user-1", "external-conversation-1", "external-message-1", "捕获上下文"),
                new TraceContext("run-1", "telegram", "external-conversation-1", "external-message-1", "conversation-1", RuntimeObservationMode.OFF),
                Path.of("workspace")
        );

        ToolExecutionResult result = new DefaultToolExecutor(toolRegistry).execute(new ToolCallRequest("capture-context", Map.of(), executionContext));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionSuccess.class, success -> {
                    assertThat(success.payload()).containsEntry("channel", "telegram");
                    assertThat(success.payload()).containsEntry("conversationId", "conversation-1");
                    assertThat(success.payload()).containsEntry("workspaceRoot", Path.of("workspace").toAbsolutePath().normalize().toString());
                });
    }

    /**
     * 构造一个最小 echo 工具，用于验证 executor 的成功与参数错误归一化行为。
     */
    private Tool createEchoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "echo",
                        "把 text 参数原样返回，便于验证最小同步工具闭环。",
                        ToolInputSchema.object(
                                Map.of("text", new ToolInputProperty("string", "需要回显的文本。")),
                                List.of("text")
                        )
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                Object text = request.arguments().get("text");
                if (!(text instanceof String value) || value.isBlank()) {
                    throw new ToolArgumentException("缺少必填参数 text。", Map.of("field", "text"));
                }
                return Map.of("echo", value);
            }
        };
    }

    /**
     * 构造一个始终抛异常的工具，用于验证 executor 对未知运行时故障的兜底收敛。
     */
    private Tool createBrokenTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "broken",
                        "用于验证异常收敛路径。",
                        ToolInputSchema.object(
                                Map.of("mode", new ToolInputProperty("string", "决定运行模式。")),
                                List.of("mode")
                        )
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /**
     * 构造一个模拟 MCP 超时的工具，用于验证执行器会保留结构化错误码而不是重新泛化。
     */
    private Tool createTimeoutTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "mcp.filesystem.read_file",
                        "用于验证 MCP 超时错误收敛。",
                        ToolInputSchema.object(
                                Map.of("path", new ToolInputProperty("string", "需要读取的文件路径。")),
                                List.of("path")
                        )
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                throw new ToolExecutionException(
                        "timeout",
                        "MCP tool timed out.",
                        Map.of("serverAlias", "filesystem")
                );
            }
        };
    }

    /**
     * 构造一个读取执行上下文的工具，用于验证 executor 不会在转发过程中丢失运行时语义。
     */
    private Tool createCaptureContextTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "capture-context",
                        "回显执行上下文，用于验证 ToolExecutionContext 透传。",
                        ToolInputSchema.object(Map.of(), List.of())
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                return Map.of(
                        "channel", request.executionContext().sourceMessage().channel(),
                        "conversationId", request.executionContext().conversationId().value(),
                        "workspaceRoot", request.executionContext().workspaceRoot().toString()
                );
            }
        };
    }
}
