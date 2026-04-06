package com.quashy.openclaw4j.tool.mcp;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.Tool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 MCP 工具目录会按 server 启动策略完成初始化、discovery 和命名空间映射，而不会把失败路径直接泄露给主链路。
 */
class McpToolCatalogTest {

    /**
     * ready server discovery 到的工具必须带上 `mcp.<alias>.` 前缀，并完整保留 discovery 返回的嵌套 schema。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeDiscoveredToolsWithPrefixedNamesAndPreservedSchema() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        McpToolCatalog catalog = new McpToolCatalog(
                createMcpProperties(false),
                alias -> new StubMcpClientSession(List.of(new McpDiscoveredTool(
                        "read_file",
                        "读取文件内容。",
                        createNestedSchema()
                ))),
                publisher
        );

        assertThat(catalog.tools())
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.definition().name()).isEqualTo("mcp.filesystem.read_file");
                    Map<String, Object> schema = tool.definition().inputSchema().schema();
                    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
                    Map<String, Object> filters = (Map<String, Object>) properties.get("filters");
                    assertThat(filters).containsEntry("type", "object");
                });
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("mcp.server.initialization.completed", "mcp.tool.discovery.completed");
    }

    /**
     * optional server 初始化失败时，系统必须继续启动并记录降级事件，同时不把该 server 的工具暴露进目录。
     */
    @Test
    void shouldDegradeWhenOptionalServerInitializationFails() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        McpToolCatalog catalog = new McpToolCatalog(
                createMcpProperties(false),
                alias -> {
                    throw new IllegalStateException("boom");
                },
                publisher
        );

        assertThat(catalog.tools()).isEmpty();
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("mcp.server.initialization.degraded");
    }

    /**
     * required server 初始化失败时必须 fail-fast，避免系统在缺少硬依赖工具时继续以不一致状态运行。
     */
    @Test
    void shouldFailFastWhenRequiredServerInitializationFails() {
        assertThatThrownBy(() -> new McpToolCatalog(
                createMcpProperties(true),
                alias -> {
                    throw new IllegalStateException("boom");
                },
                new RecordingRuntimeObservationPublisher()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filesystem");
    }

    /**
     * 构造最小 MCP 配置，使测试能聚焦 server 启动策略而不是重复声明无关字段。
     */
    private OpenClawProperties.McpProperties createMcpProperties(boolean required) {
        return new OpenClawProperties.McpProperties(
                Duration.ofSeconds(8),
                Map.of("filesystem", new OpenClawProperties.McpServerProperties(
                        "cmd.exe",
                        List.of("/c", "npx"),
                        Map.of("API_KEY", "test-key"),
                        "./workspace",
                        required
                ))
        );
    }

    /**
     * 构造一个带嵌套 object 约束的 discovery schema，用于验证 catalog 不会把复杂参数结构压平成摘要字段。
     */
    private Map<String, Object> createNestedSchema() {
        Map<String, Object> filtersSchema = new LinkedHashMap<>();
        filtersSchema.put("type", "object");
        filtersSchema.put("properties", Map.of(
                "path", Map.of("type", "string", "description", "需要读取的路径。")
        ));
        filtersSchema.put("required", List.of("path"));

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "object");
        rootSchema.put("properties", Map.of("filters", filtersSchema));
        rootSchema.put("required", List.of("filters"));
        return rootSchema;
    }

    /**
     * 记录 MCP 启动与 discovery 相关事件，便于断言降级和成功路径都会被运行期观测捕获。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 保存测试期间发出的全部事件，供断言生命周期边界和事件名称。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 为测试返回稳定 trace，避免断言受随机 runId 干扰。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext("mcp-startup", channel, externalConversationId, externalMessageId, null, RuntimeObservationMode.OFF);
        }

        /**
         * 保存摘要事件，满足 MCP 生命周期测试对事件名和 payload 的断言需求。
         */
        @Override
        public void emit(
                TraceContext traceContext,
                String eventType,
                RuntimeObservationPhase phase,
                RuntimeObservationLevel level,
                String component,
                Map<String, Object> payload,
                Map<String, Object> verbosePayload
        ) {
            events.add(new RuntimeObservationEvent(
                    Instant.parse("2026-04-06T02:00:00Z"),
                    eventType,
                    phase,
                    level,
                    component,
                    traceContext,
                    payload,
                    verbosePayload
            ));
        }
    }

    /**
     * 提供一个最小 MCP session 假实现，使测试可以只关注 catalog 对 discovery 结果和失败策略的编排。
     */
    private static final class StubMcpClientSession implements McpClientSession {

        /**
         * 承载该测试场景下希望 discovery 暴露的 MCP 工具定义列表。
         */
        private final List<McpDiscoveredTool> discoveredTools;

        /**
         * 通过构造参数注入 discovery 结果，避免测试再引入多余的 mock 框架。
         */
        private StubMcpClientSession(List<McpDiscoveredTool> discoveredTools) {
            this.discoveredTools = discoveredTools;
        }

        /**
         * 按原样返回预设工具列表，模拟 ready MCP server 在启动期 discovery 的稳定输出。
         */
        @Override
        public List<McpDiscoveredTool> listTools() {
            return discoveredTools;
        }

        /**
         * 该测试不覆盖调用路径，因此这里直接返回空 payload 即可满足最小接口契约。
         */
        @Override
        public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
            return Map.of();
        }

        /**
         * 假 session 无需真实关闭资源，但仍保留接口实现以匹配生产生命周期边界。
         */
        @Override
        public void close() {
        }
    }
}
