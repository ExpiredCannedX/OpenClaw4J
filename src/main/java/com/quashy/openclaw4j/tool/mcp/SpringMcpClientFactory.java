package com.quashy.openclaw4j.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.tool.model.ToolExecutionException;
import org.springframework.ai.mcp.client.McpClient;
import org.springframework.ai.mcp.client.McpSyncClient;
import org.springframework.ai.mcp.client.transport.ServerParameters;
import org.springframework.ai.mcp.client.transport.StdioClientTransport;
import org.springframework.ai.mcp.spec.McpSchema;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 使用 Spring MCP SDK 创建 `stdio` client session，把第三方 transport 与 schema 类型收敛在单一适配层。
 */
public class SpringMcpClientFactory implements McpClientFactory {

    /**
     * 用于把 MCP content 与 schema 统一转换成 JSON 风格 map，避免每次调用都重复构造类型引用。
     */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 承载全部 MCP server 的集中配置，便于按 alias 创建对应 `stdio` transport 与请求超时。
     */
    private final OpenClawProperties.McpProperties properties;

    /**
     * 通过构造注入集中配置，使工厂在创建 session 时无需再额外传递零散参数。
     */
    public SpringMcpClientFactory(OpenClawProperties.McpProperties properties) {
        this.properties = properties;
    }

    /**
     * 为指定 alias 创建并初始化一个基于 `stdio` transport 的同步 MCP client session。
     */
    @Override
    public McpClientSession create(String serverAlias) {
        OpenClawProperties.McpServerProperties serverProperties = properties.servers().get(serverAlias);
        if (serverProperties == null) {
            throw new IllegalArgumentException("Missing MCP server configuration for alias: " + serverAlias);
        }
        if (!StringUtils.hasText(serverProperties.command())) {
            throw new IllegalArgumentException("MCP server command must not be blank for alias: " + serverAlias);
        }
        ServerParameters serverParameters = ServerParameters.builder(serverProperties.command())
                .args(serverProperties.args())
                .env(serverProperties.env())
                .build();
        WorkingDirectoryStdioClientTransport transport = new WorkingDirectoryStdioClientTransport(
                serverParameters,
                serverProperties.workingDirectory()
        );
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(properties.requestTimeout())
                .build();
        client.initialize();
        return new SpringMcpClientSession(serverAlias, client, properties.requestTimeout());
    }

    /**
     * 适配 Spring MCP SDK 的同步 client，使目录构建和执行链路只依赖仓库内部定义的小接口。
     */
    private static final class SpringMcpClientSession implements McpClientSession {

        /**
         * 标识当前 session 绑定的 server alias，便于把远端错误收敛成内部稳定细节字段。
         */
        private final String serverAlias;

        /**
         * 承载已初始化的同步 MCP client，用于执行 tool discovery 与 invocation。
         */
        private final McpSyncClient client;

        /**
         * 记录当前 session 使用的请求超时预算，便于超时失败时回填明确诊断语义。
         */
        private final Duration requestTimeout;

        /**
         * 使用稳定 ObjectMapper 把 MCP 内容对象转换成内部统一的 JSON 风格 payload。
         */
        private final ObjectMapper objectMapper = new ObjectMapper();

        /**
         * 通过显式注入 alias、client 和超时预算，保证调用异常可以被精确映射为结构化错误。
         */
        private SpringMcpClientSession(String serverAlias, McpSyncClient client, Duration requestTimeout) {
            this.serverAlias = serverAlias;
            this.client = client;
            this.requestTimeout = requestTimeout;
        }

        /**
         * 把 SDK discovery 结果映射为内部稳定记录，避免目录构建层直接依赖第三方 schema 类型。
         */
        @Override
        public List<McpDiscoveredTool> listTools() {
            try {
                return client.listTools().tools().stream()
                        .map(tool -> new McpDiscoveredTool(
                                tool.name(),
                                tool.description(),
                                toJsonSchemaMap(tool.inputSchema())
                        ))
                        .toList();
            } catch (RuntimeException exception) {
                throw mapFailure("discovery failed for server: " + serverAlias, exception);
            }
        }

        /**
         * 调用远端 MCP tool，并把内容列表收敛为统一 payload；远端错误、超时和 transport 异常都会转成结构化执行异常。
         */
        @Override
        public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
            try {
                McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
                List<Map<String, Object>> normalizedContent = result.content().stream()
                        .map(content -> objectMapper.convertValue(content, MAP_TYPE))
                        .toList();
                if (Boolean.TRUE.equals(result.isError())) {
                    throw new ToolExecutionException(
                            "execution_failed",
                            "MCP tool reported execution failure.",
                            Map.of(
                                    "serverAlias", serverAlias,
                                    "content", normalizedContent
                            )
                    );
                }
                return Map.of(
                        "content", normalizedContent,
                        "isError", false
                );
            } catch (ToolExecutionException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw mapFailure("MCP tool invocation failed for: " + toolName, exception);
            }
        }

        /**
         * 关闭底层同步 client，让其触发 transport 关闭与子进程清理。
         */
        @Override
        public void close() {
            client.close();
        }

        /**
         * 把 SDK 的 JsonSchema 对象转换成仓库内部统一使用的 JSON 风格 map 表达。
         */
        private Map<String, Object> toJsonSchemaMap(Object jsonSchema) {
            return objectMapper.convertValue(jsonSchema, MAP_TYPE);
        }

        /**
         * 根据异常链粗分超时与通用 transport 失败，确保执行器能保留足够稳定的错误语义。
         */
        private ToolExecutionException mapFailure(String fallbackMessage, RuntimeException exception) {
            if (containsTimeout(exception)) {
                return new ToolExecutionException(
                        "timeout",
                        "MCP request timed out after " + requestTimeout + ".",
                        Map.of("serverAlias", serverAlias)
                );
            }
            return new ToolExecutionException(
                    "transport_failure",
                    fallbackMessage,
                    Map.of(
                            "serverAlias", serverAlias,
                            "exceptionType", exception.getClass().getSimpleName()
                    )
            );
        }

        /**
         * 沿异常链检查是否包含超时语义，避免直接把所有 SDK 失败都压成同一个 transport error。
         */
        private boolean containsTimeout(Throwable throwable) {
            Throwable current = throwable;
            while (current != null) {
                if (current instanceof java.util.concurrent.TimeoutException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }

    /**
     * 在 Spring SDK 默认 `stdio` transport 之上补充工作目录能力，满足本地文件型 MCP server 的最小配置需求。
     */
    private static final class WorkingDirectoryStdioClientTransport extends StdioClientTransport {

        /**
         * 指向当前 server 进程希望使用的工作目录；为空时回退到 SDK 默认行为。
         */
        private final String workingDirectory;

        /**
         * 通过显式注入工作目录扩展默认 transport，使生产代码无需手写底层 JSON-RPC 管道。
         */
        private WorkingDirectoryStdioClientTransport(ServerParameters serverParameters, String workingDirectory) {
            super(serverParameters, new ObjectMapper());
            this.workingDirectory = workingDirectory;
        }

        /**
         * 在 SDK 默认 ProcessBuilder 基础上补齐工作目录设置，避免 filesystem 等 server 在错误目录下启动。
         */
        @Override
        protected ProcessBuilder getProcessBuilder() {
            ProcessBuilder processBuilder = super.getProcessBuilder();
            if (StringUtils.hasText(workingDirectory)) {
                processBuilder.directory(Path.of(workingDirectory).toFile());
            }
            return processBuilder;
        }
    }
}
