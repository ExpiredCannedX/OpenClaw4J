package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.domain.InternalConversationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 reminder SQLite 仓储会按设计要求持久化一次性提醒、原子 claim 到期任务，并在重启后恢复未完成调度状态。
 */
class SqliteReminderRepositoryTest {

    /**
     * 临时目录用于为每个测试生成独立 SQLite 文件，避免 reminder 状态在不同用例之间相互污染。
     */
    @TempDir
    Path tempDir;

    /**
     * 新建 reminder 后即使重建仓储实例，也必须仍能在到期时重新 claim 到该任务，保证服务重启不会丢失未完成提醒。
     */
    @Test
    void shouldPersistReminderAcrossRepositoryRecreationAndRecoverInterruptedDispatch() {
        Path databaseFile = tempDir.resolve(".openclaw/reminders.sqlite");
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-04-05T10:05:00+08:00");
        SqliteReminderRepository firstRepository = new SqliteReminderRepository(databaseFile, fixedClock());
        ReminderRecord createdReminder = firstRepository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-1"),
                "telegram",
                scheduledAt,
                "十点零五提醒我开会"
        ));

        assertThat(firstRepository.claimDueReminders(scheduledAt.plusSeconds(1), 10))
                .singleElement()
                .extracting(ReminderRecord::reminderId)
                .isEqualTo(createdReminder.reminderId());

        SqliteReminderRepository restartedRepository = new SqliteReminderRepository(databaseFile, fixedClock());
        restartedRepository.recoverInterruptedDispatches();

        assertThat(restartedRepository.claimDueReminders(scheduledAt.plusSeconds(2), 10))
                .singleElement()
                .satisfies(reminder -> {
                    assertThat(reminder.reminderId()).isEqualTo(createdReminder.reminderId());
                    assertThat(reminder.status()).isEqualTo(ReminderStatus.DISPATCHING);
                    assertThat(reminder.reminderText()).isEqualTo("十点零五提醒我开会");
                    assertThat(reminder.conversationId()).isEqualTo(new InternalConversationId("conversation-1"));
                });
    }

    /**
     * claim 成功后的 reminder 必须在同一次扫描窗口里对后续 claim 隐形，避免健康进程内把同一条提醒发送多次。
     */
    @Test
    void shouldClaimDueReminderAtMostOnceUntilStateChanges() {
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-04-05T10:05:00+08:00");
        SqliteReminderRepository repository = new SqliteReminderRepository(tempDir.resolve("once.sqlite"), fixedClock());
        ReminderRecord createdReminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-2"),
                "telegram",
                scheduledAt,
                "只发送一次"
        ));

        assertThat(repository.claimDueReminders(scheduledAt.plusSeconds(1), 10))
                .singleElement()
                .extracting(ReminderRecord::reminderId)
                .isEqualTo(createdReminder.reminderId());
        assertThat(repository.claimDueReminders(scheduledAt.plusSeconds(1), 10)).isEmpty();
    }

    /**
     * 发送失败但仍有重试预算时，仓储必须回写 attempt 计数、下次重试时间和错误码，并把状态恢复成可再次调度的 scheduled。
     */
    @Test
    void shouldRescheduleFailedReminderWithIncrementedAttemptCount() {
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-04-05T10:05:00+08:00");
        OffsetDateTime nextAttemptAt = scheduledAt.plusMinutes(3);
        SqliteReminderRepository repository = new SqliteReminderRepository(tempDir.resolve("retry.sqlite"), fixedClock());
        ReminderRecord createdReminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-3"),
                "telegram",
                scheduledAt,
                "失败后重试"
        ));

        repository.claimDueReminders(scheduledAt.plusSeconds(1), 10);
        repository.markRetryPending(createdReminder.reminderId(), "delivery_target_missing", nextAttemptAt);

        assertThat(repository.findById(createdReminder.reminderId()))
                .hasValueSatisfying(reminder -> {
                    assertThat(reminder.status()).isEqualTo(ReminderStatus.SCHEDULED);
                    assertThat(reminder.attemptCount()).isEqualTo(1);
                    assertThat(reminder.nextAttemptAt()).isEqualTo(nextAttemptAt);
                    assertThat(reminder.lastErrorCode()).isEqualTo("delivery_target_missing");
                });
    }

    /**
     * 达到终态失败后，仓储必须阻止 reminder 再次进入自动扫描队列，避免超过预算后仍被重复发送。
     */
    @Test
    void shouldMarkReminderFailedAndExcludeItFromFutureClaims() {
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-04-05T10:05:00+08:00");
        SqliteReminderRepository repository = new SqliteReminderRepository(tempDir.resolve("failed.sqlite"), fixedClock());
        ReminderRecord createdReminder = repository.create(new ReminderCreateCommand(
                new InternalConversationId("conversation-4"),
                "telegram",
                scheduledAt,
                "超过预算后失败"
        ));

        repository.claimDueReminders(scheduledAt.plusSeconds(1), 10);
        repository.markFailed(createdReminder.reminderId(), "telegram_send_failed");

        assertThat(repository.findById(createdReminder.reminderId()))
                .hasValueSatisfying(reminder -> {
                    assertThat(reminder.status()).isEqualTo(ReminderStatus.FAILED);
                    assertThat(reminder.lastErrorCode()).isEqualTo("telegram_send_failed");
                });
        assertThat(repository.claimDueReminders(scheduledAt.plusDays(1), 10)).isEmpty();
    }

    /**
     * 固定时钟让仓储写入的创建时间、更新时间和恢复逻辑在不同执行环境下都保持稳定可预测。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
