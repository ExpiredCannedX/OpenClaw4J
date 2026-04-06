package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.agent.decision.FinalReplyDecision;
import com.quashy.openclaw4j.agent.decision.ToolCallDecision;
import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.channel.telegram.TelegramOutboundClient;
import com.quashy.openclaw4j.channel.telegram.TelegramOutboundMessage;
import com.quashy.openclaw4j.reminder.schedule.ReminderHeartbeatScheduler;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Telegram 单聊创建提醒后，系统会把任务持久化到本地 SQLite，并在到期 heartbeat 时主动回发提醒文本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReminderTelegramIntegrationTest {

    /**
     * 为集成测试准备独立 workspace，保证静态规则、记忆文件和 reminder SQLite 文件都与其他测试隔离。
     */
    private static final Path WORKSPACE_ROOT = prepareWorkspaceRoot();

    /**
     * 将测试 workspace 和 Telegram/调度配置注入 Spring 上下文，确保本次集成测试走真实生产装配路径。
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("openclaw.workspace-root", () -> WORKSPACE_ROOT.toString());
        registry.add("openclaw.telegram.enabled", () -> "true");
        registry.add("openclaw.telegram.bot-token", () -> "test-token");
        registry.add("openclaw.telegram.webhook-secret", () -> "test-secret");
        registry.add("openclaw.telegram.webhook-path", () -> "/api/telegram/webhook");
        registry.add("openclaw.scheduler.heartbeat", () -> "PT24H");
        registry.add("openclaw.reminder.database-file", () -> ".openclaw/reminders.sqlite");
    }

    /**
     * 驱动真实 Telegram webhook 控制器进入统一单聊主链路，覆盖提醒创建与后续主动回发的入口边界。
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * 提供 reminder heartbeat 的真实 Spring Bean，使测试可以在推进时钟后主动触发扫描而不依赖后台定时线程。
     */
    @Autowired
    private ReminderHeartbeatScheduler reminderHeartbeatScheduler;

    /**
     * 暴露真实工具注册中心，用于断言 `reminder.create` 已经作为内置工具注册到本地目录。
     */
    @Autowired
    private ToolRegistry toolRegistry;

    /**
     * 提供可推进的测试时钟，使同一上下文内可以先创建 future reminder，再推进到到期时刻触发 heartbeat。
     */
    @Autowired
    private MutableClock mutableClock;

    /**
     * 以 mock 方式控制模型决策，让集成测试聚焦 reminder 闭环而不依赖真实大模型响应。
     */
    @MockitoBean
    private AgentModelClient agentModelClient;

    /**
     * 以 mock 方式接管 Telegram 出站，既覆盖同步回复也覆盖调度后的主动回发，而不访问真实 Bot API。
     */
    @MockitoBean
    private TelegramOutboundClient telegramOutboundClient;

    /**
     * Telegram 单聊请求命中 reminder.create 后，必须把提醒写入仓储，并在 heartbeat 到期时再次通过 Telegram 主动回发。
     */
    @Test
    void shouldCreatePersistAndProactivelyDeliverTelegramReminder() throws Exception {
        when(agentModelClient.decideNextAction(any()))
                .thenReturn(new ToolCallDecision(
                        "reminder.create",
                        Map.of(
                                "text", "十点零五提醒我开会",
                                "scheduledAt", "2026-04-05T10:05:00+08:00"
                        )
                ))
                .thenReturn(new FinalReplyDecision("提醒已经创建完成"));
        when(agentModelClient.generateFinalReply(any())).thenReturn("好的，到时提醒你。");

        mockMvc.perform(post("/api/telegram/webhook")
                        .header("X-Telegram-Bot-Api-Secret-Token", "test-secret")
                        .contentType("application/json")
                        .content("""
                                {
                                  "update_id": 1001,
                                  "message": {
                                    "message_id": 3001,
                                    "text": "请在十点零五提醒我开会",
                                    "chat": {
                                      "id": 2001,
                                      "type": "private"
                                    },
                                    "from": {
                                      "id": 4001
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(toolRegistry.findByName("reminder.create")).isPresent();
        assertThat(countPersistedReminders()).isEqualTo(1);

        mutableClock.advance(Duration.ofMinutes(6));
        reminderHeartbeatScheduler.dispatchDueReminders();

        ArgumentCaptor<TelegramOutboundMessage> messageCaptor = ArgumentCaptor.forClass(TelegramOutboundMessage.class);
        verify(telegramOutboundClient, times(2)).sendMessage(messageCaptor.capture());
        List<TelegramOutboundMessage> outboundMessages = messageCaptor.getAllValues();
        assertThat(outboundMessages)
                .containsExactly(
                        new TelegramOutboundMessage(2001L, "好的，到时提醒你。"),
                        new TelegramOutboundMessage(2001L, "十点零五提醒我开会")
                );
        assertThat(countDeliveredReminders()).isEqualTo(1);
    }

    /**
     * 统计当前 reminder SQLite 中的任务总数，用于断言 webhook 处理完成后确实发生了持久化写入。
     */
    private long countPersistedReminders() {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + WORKSPACE_ROOT.resolve(".openclaw/reminders.sqlite"));
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM reminders")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Failed to count persisted reminders for integration test.", exception);
        }
    }

    /**
     * 统计当前已经进入 delivered 状态的 reminder 数量，用于断言 heartbeat 主动回发后状态机已经落账完成。
     */
    private long countDeliveredReminders() {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + WORKSPACE_ROOT.resolve(".openclaw/reminders.sqlite"));
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM reminders WHERE status = 'delivered'")) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Failed to count delivered reminders for integration test.", exception);
        }
    }

    /**
     * 在测试类加载时创建最小 workspace 文件集，避免 WorkspaceLoader 因缺少规则或记忆文件而改变主链路行为。
     */
    private static Path prepareWorkspaceRoot() {
        try {
            Path workspaceRoot = Files.createTempDirectory("openclaw-reminder-it");
            Files.writeString(workspaceRoot.resolve("SOUL.md"), "保持专业");
            Files.writeString(workspaceRoot.resolve("SKILLS.md"), "按需使用本地技能");
            Files.writeString(workspaceRoot.resolve("USER.md"), "测试用户画像");
            Files.writeString(workspaceRoot.resolve("MEMORY.md"), "测试长期记忆");
            return workspaceRoot;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to prepare integration test workspace.", exception);
        }
    }

    /**
     * 为集成测试提供可推进的统一时间源，让 webhook 创建和 heartbeat 扫描共享同一个可控时钟实例。
     */
    @TestConfiguration
    static class TestClockConfiguration {

        /**
         * 返回一个以 Asia/Shanghai 为基准的可变时钟，确保 reminder 时间判断与测试断言使用同一时区。
         */
        @Bean
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }

        /**
         * 把可变时钟同时暴露为应用级 `Clock` Bean，使 ReminderService 和 Scheduler 都自动共享这一实例。
         */
        @Bean
        @Primary
        Clock testApplicationClock(MutableClock mutableClock) {
            return mutableClock;
        }
    }

    /**
     * 提供一个可手动推进的测试时钟，使同一应用上下文内的创建与调度阶段可以共享连续但可控的时间线。
     */
    static final class MutableClock extends Clock {

        /**
         * 保存当前测试瞬时值，所有注入该时钟的组件都会读取这里的最新时间。
         */
        private Instant currentInstant;

        /**
         * 保存当前测试时钟所处时区，确保 `OffsetDateTime.now(clock)` 的结果与生产语义一致。
         */
        private final ZoneId zoneId;

        /**
         * 通过显式初始时间和时区构造可变时钟，避免集成测试依赖机器当前系统时间。
         */
        private MutableClock(Instant initialInstant, ZoneId zoneId) {
            this.currentInstant = initialInstant;
            this.zoneId = zoneId;
        }

        /**
         * 返回当前时区视图下的时钟副本，保持与 `Clock` 抽象约定一致。
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        /**
         * 返回当前时钟对应的时区标识，供 reminder 逻辑在格式化和比较时间时复用。
         */
        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        /**
         * 返回当前测试瞬时值，使所有注入组件都看到同一条可推进时间线。
         */
        @Override
        public Instant instant() {
            return currentInstant;
        }

        /**
         * 按给定时长推进当前测试时钟，便于在同一上下文内模拟“提醒到期前”和“到期后”的两个阶段。
         */
        void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }
    }
}
