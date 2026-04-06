package com.quashy.openclaw4j.conversation.infrastructure.sqlite;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.conversation.ConversationDeliveryTarget;
import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.port.ConversationDeliveryTargetRepository;
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
import java.util.Optional;

/**
 * 使用与 reminder 任务相同的本地 SQLite 文件维护内部会话到渠道目标的绑定，让重启后的异步任务仍能解析回发目标。
 */
@Repository
public class SqliteConversationDeliveryTargetRepository implements ConversationDeliveryTargetRepository {

    /**
     * 指向当前会话绑定事实源 SQLite 文件的稳定路径，保证绑定数据与 reminder 任务共享同一恢复边界。
     */
    private final Path databaseFile;

    /**
     * 提供统一时间源，使绑定刷新时间在生产和测试环境都拥有明确口径。
     */
    private final Clock clock;

    /**
     * 通过应用级集中配置解析 reminder SQLite 文件路径，保证生产装配始终落到 workspace 约定目录。
     */
    @Autowired
    public SqliteConversationDeliveryTargetRepository(OpenClawProperties properties) {
        this(resolveDatabaseFile(properties), Clock.systemDefaultZone());
    }

    /**
     * 允许测试显式指定 SQLite 文件和时钟来源，从而稳定验证绑定持久化与重启恢复语义。
     */
    public SqliteConversationDeliveryTargetRepository(Path databaseFile, Clock clock) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * 以内部会话为键写入或覆盖当前渠道目标，确保同一会话的新入站消息总能刷新到最新目标位置。
     */
    @Override
    public void save(ConversationDeliveryTarget target) {
        OffsetDateTime updatedAt = target.updatedAt() != null ? target.updatedAt() : OffsetDateTime.now(clock);
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation_delivery_targets(conversation_id, channel, external_conversation_id, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(conversation_id) DO UPDATE SET
                        channel = excluded.channel,
                        external_conversation_id = excluded.external_conversation_id,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, target.conversationId().value());
                statement.setString(2, target.channel());
                statement.setString(3, target.externalConversationId());
                statement.setString(4, updatedAt.toString());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save conversation delivery target.", exception);
        }
    }

    /**
     * 按内部会话读取当前绑定目标，为 Scheduler 等异步子系统提供平台无关的解析入口。
     */
    @Override
    public Optional<ConversationDeliveryTarget> findByConversationId(InternalConversationId conversationId) {
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT conversation_id, channel, external_conversation_id, updated_at
                    FROM conversation_delivery_targets
                    WHERE conversation_id = ?
                    """)) {
                statement.setString(1, conversationId.value());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ConversationDeliveryTarget(
                            new InternalConversationId(resultSet.getString("conversation_id")),
                            resultSet.getString("channel"),
                            resultSet.getString("external_conversation_id"),
                            OffsetDateTime.parse(resultSet.getString("updated_at"))
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load conversation delivery target.", exception);
        }
    }

    /**
     * 打开 SQLite 连接并确保父目录存在，使首个回发目标绑定能在没有预创建目录的情况下直接落盘。
     */
    private Connection openConnection() throws SQLException {
        try {
            Path parent = databaseFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create delivery target database directory.", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    }

    /**
     * 确保会话绑定表存在，使 DirectMessageService 在首次刷新绑定时不依赖额外初始化步骤。
     */
    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_delivery_targets (
                        conversation_id TEXT PRIMARY KEY,
                        channel TEXT NOT NULL,
                        external_conversation_id TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
        }
    }

    /**
     * 根据集中配置解析 SQLite 文件路径，并在相对路径场景下以 workspace 根目录作为唯一基准。
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