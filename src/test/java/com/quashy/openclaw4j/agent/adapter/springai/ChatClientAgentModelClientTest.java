package com.quashy.openclaw4j.agent.adapter.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashy.openclaw4j.agent.decision.AgentModelDecision;
import com.quashy.openclaw4j.agent.decision.ToolCallDecision;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证模型决策解析在 tool_call 场景下会严格要求 arguments 字段存在且为 JSON 对象，避免缺参请求被静默放行。
 */
class ChatClientAgentModelClientTest {

    /**
     * 当模型返回的 tool_call 缺少 arguments 时，解析层必须立即失败并给出明确错误，防止无效调用进入工具执行阶段。
     */
    @Test
    void shouldRejectToolCallWithoutArguments() {
        ChatClientAgentModelClient client = createClient();

        assertThatThrownBy(() -> invokeParseDecision(client, "{\"type\":\"tool_call\",\"toolName\":\"mcp.filesystem.list_directory\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型工具调用缺少有效字段: arguments");
    }

    /**
     * 当模型把 arguments 输出为非对象结构时，解析层必须拒绝该决策，避免参数形态错误下沉到远端 MCP。
     */
    @Test
    void shouldRejectToolCallWhenArgumentsIsNotObject() {
        ChatClientAgentModelClient client = createClient();

        assertThatThrownBy(() -> invokeParseDecision(client, "{\"type\":\"tool_call\",\"toolName\":\"mcp.filesystem.list_directory\",\"arguments\":\"/tmp\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型工具参数必须是 JSON 对象");
    }

    /**
     * 当模型返回合法对象参数时，解析结果应保留参数键值供后续工具执行直接使用。
     */
    @Test
    void shouldParseToolCallWhenArgumentsIsObject() {
        ChatClientAgentModelClient client = createClient();

        AgentModelDecision decision = invokeParseDecision(
                client,
                "{\"type\":\"tool_call\",\"toolName\":\"mcp.filesystem.list_directory\",\"arguments\":{\"path\":\"/workspace\"}}"
        );

        assertThat(decision).isInstanceOf(ToolCallDecision.class);
        ToolCallDecision toolCallDecision = (ToolCallDecision) decision;
        assertThat(toolCallDecision.toolName()).isEqualTo("mcp.filesystem.list_directory");
        assertThat(toolCallDecision.arguments()).containsEntry("path", "/workspace");
    }

    /**
     * 构造仅用于决策解析测试的客户端实例，避免测试依赖真实模型调用链路。
     */
    private ChatClientAgentModelClient createClient() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new ChatClientAgentModelClient(builder, new ObjectMapper());
    }

    /**
     * 通过反射调用 parseDecision 私有方法，精确验证 JSON 解析与字段校验策略而不引入额外调用噪音。
     */
    private AgentModelDecision invokeParseDecision(ChatClientAgentModelClient client, String rawDecision) {
        try {
            Method method = ChatClientAgentModelClient.class.getDeclaredMethod("parseDecision", String.class);
            method.setAccessible(true);
            return (AgentModelDecision) method.invoke(client, rawDecision);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("parseDecision 反射调用失败。", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法访问 parseDecision。", exception);
        }
    }
}

