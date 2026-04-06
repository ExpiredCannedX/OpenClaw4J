package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.channel.outbound.ConversationDeliveryFailureException;
import com.quashy.openclaw4j.channel.outbound.ProactiveConversationSender;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 reminder heartbeat 调度器会扫描到期任务、主动回发成功路径，以及失败后的重试和终态失败流转。
 */
class ReminderHeartbeatSchedulerTest {

    /**
     * 临时目录用于为每个测试生成独立 SQLite 文件，确保不同提醒状态不会在测试之间串扰。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * 到期提醒发送成功后必须被标记为 delivered，并发出扫描与投递成功事件供运行期观测消费。
     */
    @Test
    void shouldDeliverDueReminderAndMarkItDelivered() {
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        ReminderRecord reminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-1"),
                "telegram",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00"),
                "十点提醒"
        ));
        RecordingProactiveConversationSender sender = new RecordingProactiveConversationSender();
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        ReminderHeartbeatScheduler scheduler = new ReminderHeartbeatScheduler(
                repository,
                sender,
                createProperties(),
                publisher,
                fixedClock()
        );

        scheduler.dispatchDueReminders();

        assertThat(sender.sentDeliveries)
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.conversationId()).isEqualTo(new InternalConversationId("conversation-1"));
                    assertThat(delivery.text()).isEqualTo("十点提醒");
                });
        assertThat(repository.findById(reminder.reminderId()))
                .hasValueSatisfying(savedReminder -> assertThat(savedReminder.status()).isEqualTo(ReminderStatus.DELIVERED));
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.scheduler.scan_started", "reminder.scheduler.scan_completed", "reminder.dispatch.delivered");
    }

    /**
     * 在仍有重试预算时，调度器必须把失败任务重新排回 scheduled，并记录下一次尝试时间与错误码。
     */
    @Test
    void shouldRescheduleReminderWhenDeliveryFailsBeforeRetryBudgetIsExhausted() {
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        ReminderRecord reminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-2"),
                "telegram",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00"),
                "缺失绑定后重试"
        ));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        ReminderHeartbeatScheduler scheduler = new ReminderHeartbeatScheduler(
                repository,
                new FailingProactiveConversationSender("delivery_target_missing"),
                createProperties(),
                publisher,
                fixedClock()
        );

        scheduler.dispatchDueReminders();

        assertThat(repository.findById(reminder.reminderId()))
                .hasValueSatisfying(savedReminder -> {
                    assertThat(savedReminder.status()).isEqualTo(ReminderStatus.SCHEDULED);
                    assertThat(savedReminder.attemptCount()).isEqualTo(1);
                    assertThat(savedReminder.lastErrorCode()).isEqualTo("delivery_target_missing");
                    assertThat(savedReminder.nextAttemptAt()).isEqualTo(OffsetDateTime.parse("2026-04-05T10:03:00+08:00"));
                });
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.dispatch.retry_scheduled");
    }

    /**
     * 当失败预算已经耗尽时，调度器必须把任务写成终态 failed，避免 reminder 继续无限进入 heartbeat 扫描。
     */
    @Test
    void shouldMarkReminderFailedWhenRetryBudgetIsExhausted() {
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        ReminderRecord reminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-3"),
                "telegram",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00"),
                "超过预算终态失败"
        ));
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        ReminderHeartbeatScheduler scheduler = new ReminderHeartbeatScheduler(
                repository,
                new FailingProactiveConversationSender("telegram_send_failed"),
                createPropertiesWithRetryBudget(0),
                publisher,
                fixedClock()
        );

        scheduler.dispatchDueReminders();

        assertThat(repository.findById(reminder.reminderId()))
                .hasValueSatisfying(savedReminder -> {
                    assertThat(savedReminder.status()).isEqualTo(ReminderStatus.FAILED);
                    assertThat(savedReminder.attemptCount()).isEqualTo(1);
                    assertThat(savedReminder.lastErrorCode()).isEqualTo("telegram_send_failed");
                });
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.dispatch.failed");
    }

    /**
     * 构造带默认重试预算和退避时间的集中配置对象，避免每个测试重复手写 reminder/scheduler 参数。
     */
    private OpenClawProperties createProperties() {
        return createPropertiesWithRetryBudget(1);
    }

    /**
     * 允许测试显式指定重试预算，从而覆盖“可重试”和“终态失败”两条状态流转分支。
     */
    private OpenClawProperties createPropertiesWithRetryBudget(int maxRetryAttempts) {
        return new OpenClawProperties(
                workspaceRoot.toString(),
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                new OpenClawProperties.McpProperties(Duration.ofSeconds(20), Map.of()),
                new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160),
                new OpenClawProperties.OrchestrationProperties(4),
                new OpenClawProperties.ReminderProperties(".openclaw/reminders.sqlite"),
                new OpenClawProperties.SchedulerProperties(Duration.ofSeconds(15), 10, maxRetryAttempts, Duration.ofMinutes(3)),
                new OpenClawProperties.MemoryProperties(".openclaw/memory-index.sqlite"),
                new OpenClawProperties.ToolSafetyProperties(null, null, null, null)
        );
    }

    /**
     * 固定时钟保证 heartbeat 当前时间、退避时间和状态更新时间在所有执行环境下都保持稳定。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 记录主动回发请求，便于断言 Scheduler 成功路径确实把文本发送给了解析出的内部会话。
     */
    private static final class RecordingProactiveConversationSender implements ProactiveConversationSender {

        /**
         * 保存测试期间成功发送的全部内部会话与文本组合，供断言投递闭环是否发生。
         */
        private final List<SentDelivery> sentDeliveries = new ArrayList<>();

        /**
         * 记录一次成功发送的内部会话与文本，而不引入任何真实渠道实现。
         */
        @Override
        public void sendText(InternalConversationId conversationId, String text) {
            sentDeliveries.add(new SentDelivery(conversationId, text));
        }
    }

    /**
     * 提供一个稳定抛出业务失败异常的主动回发实现，用于覆盖 Scheduler 的重试和终态失败路径。
     */
    private static final class FailingProactiveConversationSender implements ProactiveConversationSender {

        /**
         * 指定当前测试场景希望暴露的稳定错误码，使 Scheduler 可以按错误语义断言状态流转。
         */
        private final String errorCode;

        /**
         * 通过构造参数声明失败错误码，避免测试实现把不同失败场景硬编码成多个匿名类。
         */
        private FailingProactiveConversationSender(String errorCode) {
            this.errorCode = errorCode;
        }

        /**
         * 每次发送都抛出相同业务异常，模拟缺失绑定或渠道发送失败等可重试/不可重试的统一错误语义。
         */
        @Override
        public void sendText(InternalConversationId conversationId, String text) {
            throw new ConversationDeliveryFailureException(errorCode, "simulated failure");
        }
    }

    /**
     * 保存一次成功发送的断言所需数据，避免测试直接操作两个松散列表。
     */
    private record SentDelivery(
            /**
             * 标识本次成功发送命中的内部会话，验证 Scheduler 传给 sender 的会话语义没有丢失。
             */
            InternalConversationId conversationId,
            /**
             * 保存本次成功发送的最终提醒正文，验证仓储载荷被完整传递到主动回发层。
             */
            String text
    ) {
    }

    /**
     * 记录 scheduler 相关运行事件，便于断言扫描、投递成功、重试和终态失败都会产出明确观测。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 保存测试期间发布的全部事件，供断言 Scheduler 的关键边界行为。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 返回一个稳定 trace，上层无需依赖真实发布器生成策略即可关联事件。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext("scheduler-run", channel, externalConversationId, externalMessageId, null, RuntimeObservationMode.OFF);
        }

        /**
         * 记录摘要与详细负载，满足 reminder heartbeat 测试对事件类型和顺序的断言需求。
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
                    Instant.parse("2026-04-05T02:00:00Z"),
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
}
