package com.quashy.openclaw4j.tool.safety.infrastructure.sqlite;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.tool.safety.audit.ToolAuditLogEntry;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationStatus;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolPendingConfirmationRecord;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolRiskLevel;
import com.quashy.openclaw4j.tool.safety.port.ToolAuditLogRepository;
import com.quashy.openclaw4j.tool.safety.port.ToolConfirmationRepository;
import org.springframework.util.Assert;

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
import java.time.Instant;
import java.util.Optional;

/**
 * 使用本地 SQLite 文件持久化待确认请求和工具安全审计日志，使安全治理状态可恢复、可追溯。
 */
public class SqliteToolSafetyRepository implements ToolConfirmationRepository, ToolAuditLogRepository {

    /**
     * 指向工具安全治理 SQLite 文件的稳定路径，是待确认状态和审计日志的唯一事实源。
     */
    private final Path databaseFile;

    /**
     * 提供统一时间源，使测试可以稳定断言状态写入和过期语义。
     */
    private final Clock clock;

    /**
     * 构造基于单文件 SQLite 的工具安全仓储，并在首次使用前初始化所需表结构。
     */
    public SqliteToolSafetyRepository(Path databaseFile, Object ignoredObjectMapper, Clock clock) {
        Assert.notNull(databaseFile, "databaseFile must not be null");
        Assert.notNull(clock, "clock must not be null");
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.clock = clock;
        initializeSchema();
    }

    /**
     * 查询同会话同用户下仍然有效的活跃确认记录，供策略层限制一次只存在一个待确认项。
     */
    @Override
    public Optional<ToolPendingConfirmationRecord> findActiveConfirmation(
            InternalConversationId conversationId,
            InternalUserId userId,
            Instant now
    ) {
        String sql = """
                SELECT *
                FROM pending_tool_confirmations
                WHERE conversation_id = ?
                  AND user_id = ?
                  AND status IN ('PENDING', 'CONFIRMED')
                  AND expires_at >= ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId.value());
            statement.setString(2, userId.value());
            statement.setString(3, now.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapConfirmation(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query active tool confirmation.", exception);
        }
    }

    /**
     * 查询同会话同用户最近一次确认记录，供显式确认消息识别过期与终态冲突。
     */
    @Override
    public Optional<ToolPendingConfirmationRecord> findLatestConfirmation(
            InternalConversationId conversationId,
            InternalUserId userId
    ) {
        String sql = """
                SELECT *
                FROM pending_tool_confirmations
                WHERE conversation_id = ?
                  AND user_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId.value());
            statement.setString(2, userId.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapConfirmation(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query latest tool confirmation.", exception);
        }
    }

    /**
     * 按确认记录标识查询完整记录，供恢复执行和消费后状态更新重放同一事实。
     */
    @Override
    public Optional<ToolPendingConfirmationRecord> findConfirmationById(String confirmationId) {
        String sql = """
                SELECT *
                FROM pending_tool_confirmations
                WHERE confirmation_id = ?
                LIMIT 1
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, confirmationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapConfirmation(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query tool confirmation by id.", exception);
        }
    }

    /**
     * 以完整记录为单位插入或替换确认状态，保证服务层可以显式提交状态机转换后的快照。
     */
    @Override
    public void upsertConfirmation(ToolPendingConfirmationRecord record) {
        String sql = """
                INSERT INTO pending_tool_confirmations (
                    confirmation_id,
                    conversation_id,
                    user_id,
                    tool_name,
                    normalized_arguments_json,
                    arguments_fingerprint,
                    risk_level,
                    confirmation_policy,
                    validator_type,
                    status,
                    risk_summary,
                    created_at,
                    expires_at,
                    confirmed_at,
                    consumed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(confirmation_id) DO UPDATE SET
                    conversation_id = excluded.conversation_id,
                    user_id = excluded.user_id,
                    tool_name = excluded.tool_name,
                    normalized_arguments_json = excluded.normalized_arguments_json,
                    arguments_fingerprint = excluded.arguments_fingerprint,
                    risk_level = excluded.risk_level,
                    confirmation_policy = excluded.confirmation_policy,
                    validator_type = excluded.validator_type,
                    status = excluded.status,
                    risk_summary = excluded.risk_summary,
                    created_at = excluded.created_at,
                    expires_at = excluded.expires_at,
                    confirmed_at = excluded.confirmed_at,
                    consumed_at = excluded.consumed_at
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.confirmationId());
            statement.setString(2, record.conversationId().value());
            statement.setString(3, record.userId().value());
            statement.setString(4, record.toolName());
            statement.setString(5, record.normalizedArgumentsJson());
            statement.setString(6, record.argumentsFingerprint());
            statement.setString(7, record.riskLevel().name());
            statement.setString(8, record.confirmationPolicy().name());
            statement.setString(9, record.validatorType().name());
            statement.setString(10, record.status().name());
            statement.setString(11, record.riskSummary());
            statement.setString(12, record.createdAt().toString());
            statement.setString(13, record.expiresAt().toString());
            statement.setString(14, instantToString(record.confirmedAt()));
            statement.setString(15, instantToString(record.consumedAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to persist tool confirmation.", exception);
        }
    }

    /**
     * 追加一条结构化审计事件，使策略判定、确认状态和执行结果都能落到本地事实源。
     */
    @Override
    public void appendAuditLog(ToolAuditLogEntry entry) {
        String sql = """
                INSERT INTO tool_audit_logs (
                    event_type,
                    confirmation_id,
                    conversation_id,
                    user_id,
                    tool_name,
                    arguments_fingerprint,
                    policy_decision,
                    confirmation_status,
                    execution_outcome,
                    reason_code,
                    details_json,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.eventType());
            statement.setString(2, entry.confirmationId());
            statement.setString(3, entry.conversationId().value());
            statement.setString(4, entry.userId().value());
            statement.setString(5, entry.toolName());
            statement.setString(6, entry.argumentsFingerprint());
            statement.setString(7, entry.policyDecision());
            statement.setString(8, entry.confirmationStatus());
            statement.setString(9, entry.executionOutcome());
            statement.setString(10, entry.reasonCode());
            statement.setString(11, entry.details().toString());
            statement.setString(12, entry.createdAt().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to append tool audit log.", exception);
        }
    }

    /**
     * 打开 SQLite 连接并确保父目录存在，使首次写入待确认状态时无需预创建目录。
     */
    private Connection openConnection() throws SQLException {
        try {
            Files.createDirectories(databaseFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create tool safety database directory.", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    }

    /**
     * 初始化待确认表和审计表，保证仓储在测试和生产环境都能即插即用。
     */
    private void initializeSchema() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_tool_confirmations (
                        confirmation_id TEXT PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        tool_name TEXT NOT NULL,
                        normalized_arguments_json TEXT NOT NULL,
                        arguments_fingerprint TEXT NOT NULL,
                        risk_level TEXT NOT NULL,
                        confirmation_policy TEXT NOT NULL,
                        validator_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        risk_summary TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        confirmed_at TEXT NULL,
                        consumed_at TEXT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tool_audit_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_type TEXT NOT NULL,
                        confirmation_id TEXT NULL,
                        conversation_id TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        tool_name TEXT NOT NULL,
                        arguments_fingerprint TEXT NOT NULL,
                        policy_decision TEXT NULL,
                        confirmation_status TEXT NULL,
                        execution_outcome TEXT NULL,
                        reason_code TEXT NOT NULL,
                        details_json TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to initialize tool safety schema.", exception);
        }
    }

    /**
     * 把 SQLite 行恢复为确认记录，避免调用方直接处理 JDBC 字段与状态枚举转换细节。
     */
    private ToolPendingConfirmationRecord mapConfirmation(ResultSet resultSet) throws SQLException {
        return new ToolPendingConfirmationRecord(
                resultSet.getString("confirmation_id"),
                new InternalConversationId(resultSet.getString("conversation_id")),
                new InternalUserId(resultSet.getString("user_id")),
                resultSet.getString("tool_name"),
                resultSet.getString("normalized_arguments_json"),
                resultSet.getString("arguments_fingerprint"),
                ToolRiskLevel.valueOf(resultSet.getString("risk_level")),
                ToolConfirmationPolicy.valueOf(resultSet.getString("confirmation_policy")),
                ToolArgumentValidatorType.valueOf(resultSet.getString("validator_type")),
                ToolConfirmationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("risk_summary"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("expires_at")),
                parseInstant(resultSet.getString("confirmed_at")),
                parseInstant(resultSet.getString("consumed_at"))
        );
    }

    /**
     * 把可选时间安全转换为 ISO-8601 字符串，避免数据库层混入显式的 `null` 文本。
     */
    private String instantToString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * 把数据库中的可选 ISO 时间字段恢复为 `Instant`，未设置时返回空值。
     */
    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}

