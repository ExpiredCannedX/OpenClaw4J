package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.decision.AgentModelDecision;
import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.agent.prompt.AgentPromptAssembler;
import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.runtime.DefaultAgentFacade;
import com.quashy.openclaw4j.agent.decision.FinalReplyDecision;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.runtime.DefaultRuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.sink.NoopRuntimeObservationSink;
import com.quashy.openclaw4j.skill.SkillMarkdownParser;
import com.quashy.openclaw4j.skill.SkillResolver;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryActiveConversationRepository;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryConversationDeliveryTargetRepository;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryConversationTurnRepository;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryIdentityMappingRepository;
import com.quashy.openclaw4j.conversation.infrastructure.memory.InMemoryProcessedMessageRepository;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.workspace.FileWorkspaceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

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
        Files.writeString(workspaceRoot.resolve("SKILLS.md"), "优先使用本地 Skill");
        Files.writeString(workspaceRoot.resolve("USER.md"), "称呼用户为伙伴");
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), "记住近期问题");
        Path skillDirectory = Files.createDirectories(workspaceRoot.resolve("skills").resolve("code-review"));
        Files.writeString(skillDirectory.resolve("SKILL.md"), """
                ---
                name: code-review
                description: 审查风险
                keywords:
                  - code-review
                ---
                先看风险，再给建议。
                """);
        OpenClawProperties properties = new OpenClawProperties(
                workspaceRoot.toString(),
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                new OpenClawProperties.McpProperties(Duration.ofSeconds(20), java.util.Map.of()),
                new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160),
                new OpenClawProperties.OrchestrationProperties(4),
                new OpenClawProperties.ReminderProperties(".openclaw/reminders.sqlite"),
                new OpenClawProperties.SchedulerProperties(Duration.ofSeconds(15), 20, 3, Duration.ofMinutes(3)),
                new OpenClawProperties.MemoryProperties(".openclaw/memory-index.sqlite"),
                new OpenClawProperties.ToolSafetyProperties(null, null, null, null)
        );
        DefaultRuntimeObservationPublisher observationPublisher = new DefaultRuntimeObservationPublisher(
                RuntimeObservationMode.OFF,
                160,
                new NoopRuntimeObservationSink(),
                Clock.systemUTC()
        );
        AgentModelClient modelClient = new AgentModelClient() {
            /**
             * 对测试请求始终返回最终回复决策，确保 DirectMessageController 能走通新的结构化模型协议。
             */
            @Override
            public AgentModelDecision decideNextAction(AgentPrompt prompt) {
                return new FinalReplyDecision("已收到：" + prompt.content().contains("你好"));
            }

            /**
             * 该测试不会进入工具观察后的最终回复阶段，因此返回占位文本即可防止误调用。
             */
            @Override
            public String generateFinalReply(AgentPrompt prompt) {
                return "unexpected";
            }
        };
        LocalToolRegistry toolRegistry = new LocalToolRegistry(List.<Tool>of());
        DirectMessageService service = new DirectMessageService(
                new InMemoryIdentityMappingRepository(),
                new InMemoryActiveConversationRepository(),
                new InMemoryProcessedMessageRepository(),
                new InMemoryConversationDeliveryTargetRepository(),
                new DefaultAgentFacade(
                        new FileWorkspaceLoader(properties),
                        new AgentPromptAssembler(),
                        new SkillResolver(new SkillMarkdownParser()),
                        new InMemoryConversationTurnRepository(),
                        modelClient,
                        toolRegistry,
                        new DefaultToolExecutor(toolRegistry),
                        properties,
                        observationPublisher
                )
                ,
                observationPublisher
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
                                  "body": "请使用 $code-review 帮我看看这个改动"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("已收到：false"))
                .andExpect(jsonPath("$.signals").isArray())
                .andExpect(jsonPath("$.signals[0].type").value("skill_applied"))
                .andExpect(jsonPath("$.signals[0].payload.skill_name").value("code-review"))
                .andExpect(jsonPath("$.signals[0].payload.activation_mode").value("explicit"));
    }
}
