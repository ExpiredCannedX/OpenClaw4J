package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.channel.outbound.ConversationDeliveryFailureException;
import com.quashy.openclaw4j.channel.outbound.ProactiveConversationSender;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 以固定 heartbeat 扫描并履约一次性 reminder，使持久化任务能够在到期时触发主动文本回发。
 */
@Service
public class ReminderHeartbeatScheduler {

    /**
     * 提供 reminder 任务的 claim、状态流转和恢复能力，是 heartbeat 的唯一任务事实源。
     */
    private final SqliteReminderRepository reminderRepository;

    /**
     * 提供平台无关的主动回发能力，使调度器只依赖内部会话语义而不直接耦合具体渠道。
     */
    private final ProactiveConversationSender proactiveConversationSender;

    /**
     * 提供 heartbeat 间隔、批量扫描与失败重试预算等运行时策略。
     */
    private final OpenClawProperties properties;

    /**
     * 负责发布调度扫描和投递结果事件，便于运行期明确看到 reminder 异步履约边界。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 提供统一当前时间，使 scan、退避时间和状态更新时间在生产与测试环境下拥有一致口径。
     */
    private final Clock clock;

    /**
     * 通过显式依赖注入固定 reminder heartbeat 的边界，让扫描、回发和状态机都围绕同一事实源与网关工作。
     */
    public ReminderHeartbeatScheduler(
            SqliteReminderRepository reminderRepository,
            ProactiveConversationSender proactiveConversationSender,
            OpenClawProperties properties,
            RuntimeObservationPublisher runtimeObservationPublisher,
            Clock clock
    ) {
        this.reminderRepository = reminderRepository;
        this.proactiveConversationSender = proactiveConversationSender;
        this.properties = properties;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
        this.clock = clock;
    }

    /**
     * 在应用启动后恢复中断在 dispatching 的提醒任务，使单进程崩溃后的 reminder 能重新回到可扫描状态。
     */
    @PostConstruct
    public void recoverInterruptedDispatches() {
        reminderRepository.recoverInterruptedDispatches();
    }

    /**
     * 按配置中的固定 heartbeat 间隔触发 reminder 扫描，并把真正的逻辑委托给显式可测试的方法执行。
     */
    @Scheduled(fixedDelayString = "${openclaw.scheduler.heartbeat:PT15S}")
    public void scheduledHeartbeat() {
        dispatchDueReminders();
    }

    /**
     * claim 当前到期 reminder，逐条尝试主动回发，并根据结果写入 delivered、retry 或 failed 状态。
     */
    public void dispatchDueReminders() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        TraceContext scanTraceContext = runtimeObservationPublisher.createTrace(
                "scheduler",
                "reminder-heartbeat",
                now.toString()
        );
        runtimeObservationPublisher.emit(
                scanTraceContext,
                "reminder.scheduler.scan_started",
                RuntimeObservationPhase.SCHEDULER,
                RuntimeObservationLevel.INFO,
                "ReminderHeartbeatScheduler",
                Map.of("batchSize", properties.scheduler().scanBatchSize())
        );
        List<ReminderRecord> dueReminders = reminderRepository.claimDueReminders(now, properties.scheduler().scanBatchSize());
        runtimeObservationPublisher.emit(
                scanTraceContext,
                "reminder.scheduler.scan_completed",
                RuntimeObservationPhase.SCHEDULER,
                RuntimeObservationLevel.INFO,
                "ReminderHeartbeatScheduler",
                Map.of("claimedCount", dueReminders.size())
        );
        for (ReminderRecord reminder : dueReminders) {
            dispatchSingleReminder(scanTraceContext.withInternalConversationId(reminder.conversationId().value()), reminder, now);
        }
    }

    /**
     * 尝试履约单条 reminder，并按剩余重试预算决定进入 delivered、scheduled 或 failed 状态。
     */
    private void dispatchSingleReminder(TraceContext traceContext, ReminderRecord reminder, OffsetDateTime now) {
        try {
            proactiveConversationSender.sendText(reminder.conversationId(), reminder.reminderText());
            reminderRepository.markDelivered(reminder.reminderId());
            runtimeObservationPublisher.emit(
                    traceContext,
                    "reminder.dispatch.delivered",
                    RuntimeObservationPhase.SCHEDULER,
                    RuntimeObservationLevel.INFO,
                    "ReminderHeartbeatScheduler",
                    Map.of(
                            "reminderId", reminder.reminderId(),
                            "conversationId", reminder.conversationId().value()
                    )
            );
        } catch (ConversationDeliveryFailureException exception) {
            if (reminder.attemptCount() < properties.scheduler().maxRetryAttempts()) {
                OffsetDateTime nextAttemptAt = now.plus(properties.scheduler().retryBackoff());
                reminderRepository.markRetryPending(reminder.reminderId(), exception.errorCode(), nextAttemptAt);
                runtimeObservationPublisher.emit(
                        traceContext,
                        "reminder.dispatch.retry_scheduled",
                        RuntimeObservationPhase.SCHEDULER,
                        RuntimeObservationLevel.WARN,
                        "ReminderHeartbeatScheduler",
                        Map.of(
                                "reminderId", reminder.reminderId(),
                                "errorCode", exception.errorCode(),
                                "nextAttemptAt", nextAttemptAt.toString()
                        )
                );
                return;
            }
            reminderRepository.markFailed(reminder.reminderId(), exception.errorCode());
            runtimeObservationPublisher.emit(
                    traceContext,
                    "reminder.dispatch.failed",
                    RuntimeObservationPhase.SCHEDULER,
                    RuntimeObservationLevel.ERROR,
                    "ReminderHeartbeatScheduler",
                    Map.of(
                            "reminderId", reminder.reminderId(),
                            "errorCode", exception.errorCode()
                    )
            );
        }
    }
}
