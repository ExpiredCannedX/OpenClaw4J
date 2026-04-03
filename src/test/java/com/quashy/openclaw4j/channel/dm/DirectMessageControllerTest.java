package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.AgentPromptAssembler;
import com.quashy.openclaw4j.agent.AgentModelClient;
import com.quashy.openclaw4j.agent.DefaultAgentFacade;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.store.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.store.memory.InMemoryConversationTurnRepository;
import com.quashy.openclaw4j.store.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.store.memory.InMemoryProcessedMessageRepository;
import com.quashy.openclaw4j.workspace.FileWorkspaceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证开发用单聊 HTTP 入口已经能够把请求贯穿到统一 Agent 主链路，并返回结构化回复。
 */
class DirectMessageControllerTest {

    /**
     * 一个合法的 direct-message 请求必须被标准化、进入 Agent 核心并最终返回一次性 ReplyEnvelope。
     */
    @Test
    void shouldHandleDirectMessageRequest(@TempDir Path workspaceRoot) throws Exception {
        Files.writeString(workspaceRoot.resolve("SOUL.md"), "保持专业");
        Files.writeString(workspaceRoot.resolve("USER.md"), "称呼用户为伙伴");
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), "记住近期问题");
        OpenClawProperties properties = new OpenClawProperties(
                workspaceRoot.toString(),
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
        );
        AgentModelClient modelClient = prompt -> "已收到：" + prompt.content().contains("你好");
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new DefaultAgentFacade(
                        new FileWorkspaceLoader(properties),
                        new AgentPromptAssembler(),
                        new InMemoryConversationTurnRepository(),
                        modelClient,
                        properties
                )
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DirectMessageController(service)).build();

        mockMvc.perform(post("/api/direct-messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "channel": "dev",
                                  "externalUserId": "user-1",
                                  "externalConversationId": "dm-1",
                                  "externalMessageId": "msg-1",
                                  "body": "你好"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("已收到：true"))
                .andExpect(jsonPath("$.signals").isArray());
    }
}
