package com.quashy.openclaw4j;

import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证旧调试入口已经通过集中配置读取默认 prompt，避免继续把运行时文案硬编码在控制器内部。
 */
class ChatClientControllerTest {

    /**
     * 当调用方未传入 prompt 时，控制器必须回退到配置中的默认 prompt，而不是使用代码常量。
     */
    @Test
    void shouldUseConfiguredDefaultPromptWhenPromptIsBlank() {
        AgentModelClient agentModelClient = mock(AgentModelClient.class);
        when(agentModelClient.generateFinalReply(any(AgentPrompt.class))).thenReturn("ok");
        OpenClawProperties properties = new OpenClawProperties(
                "./workspace",
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("来自配置的默认问题"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160),
                new OpenClawProperties.MemoryProperties(".openclaw/memory-index.sqlite")
        );
        ChatClientController controller = new ChatClientController(agentModelClient, properties);

        controller.simpleChat("  ");

        ArgumentCaptor<AgentPrompt> promptCaptor = ArgumentCaptor.forClass(AgentPrompt.class);
        verify(agentModelClient).generateFinalReply(promptCaptor.capture());
        assertThat(promptCaptor.getValue().content()).isEqualTo("来自配置的默认问题");
    }
}
