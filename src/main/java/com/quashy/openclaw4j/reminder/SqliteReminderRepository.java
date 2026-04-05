package com.quashy.openclaw4j.reminder;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.InternalConversationId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用本地 SQLite 事实源维护 reminder V1 的任务状态流转，让一次性提醒在单进程重启后仍能继续参与调度。
 */
@Repository
public class SqliteReminderRepository {

    /**
     * 指向当前 reminder 事实源 SQLite 文件的稳定路径，是全部状态读写与恢复语义的唯一落点。
     */
    private final Path databaseFile;

    /**
     * 提供统一时间源，使创建时间、状态更新时间和测试断言都遵循同一时钟口径。
     */
    private final Clock clock;

    /**
     * 通过显式数据库文件与时钟注入固定仓储边界，避免实现层直接依赖静态时间或全局路径。
     */
    @Autowired
    public SqliteReminderRepository(OpenClawProperties properties, Clock clock) {
        this(resolveDatabaseFile(properties), clock);
    }

    /**
     * 允许测试显式指定 SQLite 文件与时钟来源，从而稳定验证 reminder 事实源的持久化和恢复语义。
     */
    public SqliteReminderRepository(Path databaseFile, Clock clock) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * 持久化一条新的 scheduled reminder，并返回已经分配主键与审计时间的完整记录视图。
     */
    public ReminderRecord create(ReminderCreateCommand command) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ReminderRecord reminder = new ReminderRecord(
                UUID.randomUUID().toString(),
                command.conversationId(),
                command.channel(),
                command.scheduledAt(),
                command.reminderText(),
                ReminderStatus.SCHEDULED,
                0,
                command.scheduledAt(),
                null,
                now,
                now
        );
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reminders(
                        reminder_id,
                        conversation_id,
                        channel,
                        scheduled_at,
                        reminder_text,
                        status,
                        attempt_count,
                        next_attempt_at,
                        last_error_code,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, reminder.reminderId());
                statement.setString(2, reminder.conversationId().value());
                statement.setString(3, reminder.channel());
                statement.setString(4, reminder.scheduledAt().toString());
                statement.setString(5, reminder.reminderText());
                statement.setString(6, reminder.status().databaseValue());
                statement.setInt(7, reminder.attemptCount());
                statement.setString(8, reminder.nextAttemptAt().toString());
                statement.setString(9, reminder.lastErrorCode());
                statement.setString(10, reminder.createdAt().toString());
                statement.setString(11, reminder.updatedAt().toString());
                statement.executeUpdate();
            }
            return reminder;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create reminder.", exception);
        }
    }

    /**
     * 原子 claim 当前到期且仍处于 scheduled 的 reminder，并把它们转入 dispatching 以避免同一健康进程内重复发送。
     */
    public List<ReminderRecord> claimDueReminders(OffsetDateTime now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            connection.setAutoCommit(false);
            List<ReminderRecord> claimedReminders = new ArrayList<>();
            for (ReminderRecord candidate : selectDueCandidates(connection, now, limit)) {
                if (tryClaimReminder(connection, candidate.reminderId(), now)) {
                    claimedReminders.add(new ReminderRecord(
                            candidate.reminderId(),
                            candidate.conversationId(),
                            candidate.channel(),
                            candidate.scheduledAt(),
                            candidate.reminderText(),
                            ReminderStatus.DISPATCHING,
                            candidate.attemptCount(),
                            candidate.nextAttemptAt(),
                            candidate.lastErrorCode(),
                            candidate.createdAt(),
                            now
                    ));
                }
            }
            connection.commit();
            return List.copyOf(claimedReminders);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to claim due reminders.", exception);
        }
    }

    /**
     * 将已经成功投递的 reminder 标记为 delivered，使其从后续 heartbeat 自动扫描集合中永久移除。
     */
    public void markDelivered(String reminderId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reminders
                    SET status = ?,
                        last_error_code = NULL,
                        updated_at = ?
                    WHERE reminder_id = ?
                    """)) {
                statement.setString(1, ReminderStatus.DELIVERED.databaseValue());
                statement.setString(2, now.toString());
                statement.setString(3, reminderId);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark reminder delivered.", exception);
        }
    }

    /**
     * 将一次失败后的 reminder 重新排回 scheduled，并累加失败次数、记录错误码和下次可再次尝试的时间点。
     */
    public void markRetryPending(String reminderId, String errorCode, OffsetDateTime nextAttemptAt) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reminders
                    SET status = ?,
                        attempt_count = attempt_count + 1,
                        next_attempt_at = ?,
                        last_error_code = ?,
                        updated_at = ?
                    WHERE reminder_id = ?
                    """)) {
                statement.setString(1, ReminderStatus.SCHEDULED.databaseValue());
                statement.setString(2, nextAttemptAt.toString());
                statement.setString(3, errorCode);
                statement.setString(4, now.toString());
                statement.setString(5, reminderId);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to reschedule reminder retry.", exception);
        }
    }

    /**
     * 将已经耗尽自动重试预算的 reminder 标记为 failed，并记录最后一次失败错误码供后续排障与观测使用。
     */
    public void markFailed(String reminderId, String errorCode) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reminders
                    SET status = ?,
                        attempt_count = attempt_count + 1,
                        last_error_code = ?,
                        updated_at = ?
                    WHERE reminder_id = ?
                    """)) {
                statement.setString(1, ReminderStatus.FAILED.databaseValue());
                statement.setString(2, errorCode);
                statement.setString(3, now.toString());
                statement.setString(4, reminderId);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to mark reminder failed.", exception);
        }
    }

    /**
     * 在进程重启后把中断在 dispatching 的任务重新放回 scheduled，显式接受 V1 有限重复风险以换取恢复能力。
     */
    public void recoverInterruptedDispatches() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reminders
                    SET status = ?,
                        updated_at = ?
                    WHERE status = ?
                    """)) {
                statement.setString(1, ReminderStatus.SCHEDULED.databaseValue());
                statement.setString(2, now.toString());
                statement.setString(3, ReminderStatus.DISPATCHING.databaseValue());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to recover interrupted dispatching reminders.", exception);
        }
    }

    /**
     * 读取指定 reminder 的当前事实状态，供调度器和测试在状态流转后做精确校验。
     */
    public Optional<ReminderRecord> findById(String reminderId) {
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        reminder_id,
                        conversation_id,
                        channel,
                        scheduled_at,
                        reminder_text,
                        status,
                        attempt_count,
                        next_attempt_at,
                        last_error_code,
                        created_at,
                        updated_at
                    FROM reminders
                    WHERE reminder_id = ?
                    """)) {
                statement.setString(1, reminderId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapReminder(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load reminder by id.", exception);
        }
    }

    /**
     * 打开 reminder SQLite 连接并确保父目录存在，使首次使用仓储时无需预先创建数据库目录。
     */
    private Connection openConnection() throws SQLException {
        try {
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create reminder database directory.", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    }

    /**
     * 为 reminder 事实源补齐最小表结构与查询索引，保证持久化和 heartbeat 扫描都使用同一 schema。
     */
    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS reminders (
                        reminder_id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        scheduled_at TEXT NOT NULL,
                        reminder_text TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL,
                        next_attempt_at TEXT NOT NULL,
                        last_error_code TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_reminders_status_next_attempt
                    ON reminders(status, next_attempt_at, scheduled_at)
                    """);
        }
    }

    /**
     * 读取当前到期的 scheduled 候选集合，为后续逐条原子 claim 提供稳定顺序和批量上限。
     */
    private List<ReminderRecord> selectDueCandidates(Connection connection, OffsetDateTime now, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    reminder_id,
                    conversation_id,
                    channel,
                    scheduled_at,
                    reminder_text,
                    status,
                    attempt_count,
                    next_attempt_at,
                    last_error_code,
                    created_at,
                    updated_at
                FROM reminders
                WHERE status = ?
                  AND next_attempt_at <= ?
                ORDER BY scheduled_at ASC, reminder_id ASC
                LIMIT ?
                """)) {
            statement.setString(1, ReminderStatus.SCHEDULED.databaseValue());
            statement.setString(2, now.toString());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ReminderRecord> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    candidates.add(mapReminder(resultSet));
                }
                return candidates;
            }
        }
    }

    /**
     * 以 `status + next_attempt_at` 条件保护单条 claim，保证只有仍然满足扫描条件的 reminder 会转入 dispatching。
     */
    private boolean tryClaimReminder(Connection connection, String reminderId, OffsetDateTime now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE reminders
                SET status = ?,
                    updated_at = ?
                WHERE reminder_id = ?
                  AND status = ?
                  AND next_attempt_at <= ?
                """)) {
            statement.setString(1, ReminderStatus.DISPATCHING.databaseValue());
            statement.setString(2, now.toString());
            statement.setString(3, reminderId);
            statement.setString(4, ReminderStatus.SCHEDULED.databaseValue());
            statement.setString(5, now.toString());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * 把 SQLite 查询结果映射为稳定的领域记录，避免调度层直接处理 JDBC 字段和时间解析细节。
     */
    private ReminderRecord mapReminder(ResultSet resultSet) throws SQLException {
        return new ReminderRecord(
                resultSet.getString("reminder_id"),
                new InternalConversationId(resultSet.getString("conversation_id")),
                resultSet.getString("channel"),
                OffsetDateTime.parse(resultSet.getString("scheduled_at")),
                resultSet.getString("reminder_text"),
                ReminderStatus.fromDatabaseValue(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                OffsetDateTime.parse(resultSet.getString("next_attempt_at")),
                resultSet.getString("last_error_code"),
                OffsetDateTime.parse(resultSet.getString("created_at")),
                OffsetDateTime.parse(resultSet.getString("updated_at"))
        );
    }

    /**
     * 根据集中配置解析 reminder SQLite 文件路径，并在相对路径场景下以 workspace 根目录作为唯一基准。
     */
    private static Path resolveDatabaseFile(OpenClawProperties properties) {
        Path configuredPath = Path.of(properties.reminder().databaseFile());
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize();
        }
        return Path.of(properties.workspaceRoot())
                .toAbsolutePath()
                .normalize()
                .resolve(configuredPath)
                .normalize();
    }
}
