package com.quashy.openclaw4j.memory;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.memory.index.SqliteMemoryIndexer;
import com.quashy.openclaw4j.memory.model.MemorySearchMatch;
import com.quashy.openclaw4j.memory.model.MemorySearchRequest;
import com.quashy.openclaw4j.memory.model.MemorySearchScope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 SQLite memory indexer 会以显式 trigram FTS 索引支撑 memory.search，并保持 scope 过滤与旧索引重建语义稳定。
 */
class SqliteMemoryIndexerTest {

    /**
     * 临时工作区目录用于生成独立 SQLite 文件和 Markdown 事实源，保证测试之间互不干扰。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * 长于 trigram 下界的中文查询必须走 FTS `MATCH` 主路径，并返回正向相关度分数而不是固定占位值。
     */
    @Test
    void shouldUseTrigramMatchForLongChineseQueryAndReturnPositiveScore() throws IOException {
        Files.writeString(workspaceRoot.resolve("USER.md"), """
                # 用户画像

                ## 偏好

                - 用户喜欢黑咖啡，也会关注手冲风味
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                """);
        SqliteMemoryIndexer indexer = createIndexer();

        indexer.refreshChangedFiles();

        List<MemorySearchMatch> matches = indexer.search(new MemorySearchRequest(
                "黑咖啡",
                MemorySearchScope.ALL,
                createExecutionContext("conversation-1")
        ));

        assertThat(matches)
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.relativePath()).isEqualTo("USER.md");
                    assertThat(match.targetBucket()).isEqualTo("user_profile");
                    assertThat(match.previewSnippet()).contains("黑咖啡");
                    assertThat(match.score()).isPositive();
                    assertThat(match.lineStart()).isGreaterThan(0);
                    assertThat(match.lineEnd()).isGreaterThanOrEqualTo(match.lineStart());
                });
    }

    /**
     * trigram 无法直接命中的短中文查询必须稳定回退到受控的 `LIKE` 补偿路径，避免中文片段检索退化。
     */
    @Test
    void shouldFallbackToLikeForShortChineseQuery() throws IOException {
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), """
                # 长期记忆

                - 用户喜欢黑咖啡
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                """);
        SqliteMemoryIndexer indexer = createIndexer();

        indexer.refreshChangedFiles();

        List<MemorySearchMatch> matches = indexer.search(new MemorySearchRequest(
                "咖啡",
                MemorySearchScope.ALL,
                createExecutionContext("conversation-1")
        ));

        assertThat(matches)
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.relativePath()).isEqualTo("MEMORY.md");
                    assertThat(match.previewSnippet()).contains("黑咖啡");
                    assertThat(match.score()).isPositive();
                });
    }

    /**
     * 混合查询必须让长词走 `MATCH`、短词走补偿过滤，确保短词约束不会在主检索路径中被忽略。
     */
    @Test
    void shouldApplyShortTermCompensationForMixedQuery() throws IOException {
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), """
                # 长期记忆

                - 用户每周都会喝黑咖啡
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed

                - 用户每天都会喝黑咖啡
                  - written_at: 2026-04-04T11:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                """);
        SqliteMemoryIndexer indexer = createIndexer();

        indexer.refreshChangedFiles();

        List<MemorySearchMatch> matches = indexer.search(new MemorySearchRequest(
                "  黑咖啡   周 ",
                MemorySearchScope.ALL,
                createExecutionContext("conversation-1")
        ));

        assertThat(matches)
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.relativePath()).isEqualTo("MEMORY.md");
                    assertThat(match.previewSnippet()).contains("每周");
                    assertThat(match.previewSnippet()).doesNotContain("每天");
                    assertThat(match.score()).isPositive();
                });
    }

    /**
     * session scope 查询必须只返回当前内部会话的日志块，避免不同会话的临时记忆互相污染。
     */
    @Test
    void shouldRestrictSessionScopeToCurrentConversation() throws IOException {
        Path memoryDirectory = workspaceRoot.resolve("memory");
        Files.createDirectories(memoryDirectory);
        Files.writeString(memoryDirectory.resolve("2026-04-04.md"), """
                # 会话记忆 2026-04-04

                - 需要准备路线图评审材料
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                  - conversation_id: conversation-1

                - 需要安排宠物就诊
                  - written_at: 2026-04-04T11:00:00+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                  - conversation_id: conversation-2
                """);
        SqliteMemoryIndexer indexer = createIndexer();

        indexer.refreshChangedFiles();

        List<MemorySearchMatch> matches = indexer.search(new MemorySearchRequest(
                "需要",
                MemorySearchScope.SESSION,
                createExecutionContext("conversation-1")
        ));

        assertThat(matches)
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.relativePath()).isEqualTo("memory/2026-04-04.md");
                    assertThat(match.previewSnippet()).contains("路线图评审材料");
                    assertThat(match.previewSnippet()).doesNotContain("宠物就诊");
                    assertThat(match.targetBucket()).isEqualTo("session_log");
                });
    }

    /**
     * 遇到旧 schema 或旧 FTS 定义时，索引必须整体重建为显式 trigram 版本，而不是保留旧虚表继续服务查询。
     */
    @Test
    void shouldRebuildLegacyIndexWhenSchemaVersionOrFtsDefinitionDiffers() throws Exception {
        Path indexFile = workspaceRoot.resolve(".openclaw/memory-index.sqlite");
        Files.writeString(workspaceRoot.resolve("USER.md"), """
                # 用户画像

                ## 偏好

                - 用户喜欢黑咖啡
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                """);
        seedLegacyIndex(indexFile, "USER.md", Files.readString(workspaceRoot.resolve("USER.md")));
        SqliteMemoryIndexer indexer = createIndexer();

        indexer.refreshChangedFiles();

        assertThat(readUserVersion(indexFile)).isPositive();
        assertThat(readFtsDefinition(indexFile)).contains("tokenize='trigram'");
        assertThat(indexer.search(new MemorySearchRequest(
                "黑咖啡",
                MemorySearchScope.ALL,
                createExecutionContext("conversation-1")
        ))).hasSize(1);
    }

    /**
     * 统一创建带固定时钟的 indexer，避免每个测试重复声明相同的路径和时间依赖。
     */
    private SqliteMemoryIndexer createIndexer() {
        return new SqliteMemoryIndexer(
                workspaceRoot,
                workspaceRoot.resolve(".openclaw/memory-index.sqlite"),
                fixedClock()
        );
    }

    /**
     * 写入一个不带 trigram 与 schema version 的旧索引文件，用来验证升级路径会强制整体重建。
     */
    private void seedLegacyIndex(Path indexFile, String relativePath, String content) throws SQLException {
        try (Connection connection = openConnection(indexFile);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS memory_files (
                        relative_path TEXT PRIMARY KEY,
                        content_hash TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        indexed_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS memory_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        relative_path TEXT NOT NULL,
                        target_bucket TEXT NOT NULL,
                        conversation_id TEXT,
                        line_start INTEGER NOT NULL,
                        line_end INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        provenance_summary TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS memory_chunks_fts USING fts5(
                        content,
                        provenance_summary,
                        content='memory_chunks',
                        content_rowid='id'
                    )
                    """);
            try (PreparedStatement upsertFile = connection.prepareStatement("""
                    INSERT INTO memory_files(relative_path, content_hash, updated_at, indexed_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(relative_path) DO UPDATE SET
                        content_hash = excluded.content_hash,
                        updated_at = excluded.updated_at,
                        indexed_at = excluded.indexed_at
                    """)) {
                upsertFile.setString(1, relativePath);
                upsertFile.setString(2, sha256(content));
                upsertFile.setString(3, "2026-04-04T10:15:30+08:00");
                upsertFile.setString(4, "2026-04-04T10:15:30+08:00");
                upsertFile.executeUpdate();
            }
        }
    }

    /**
     * 读取 SQLite 的 schema version，以验证升级逻辑确实写入了可判定的版本元数据。
     */
    private int readUserVersion(Path indexFile) throws SQLException {
        try (Connection connection = openConnection(indexFile);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    /**
     * 读取当前 FTS 虚表定义，确保旧索引在升级后已经被替换为显式 trigram tokenizer。
     */
    private String readFtsDefinition(Path indexFile) throws SQLException {
        try (Connection connection = openConnection(indexFile);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT sql
                     FROM sqlite_master
                     WHERE type = 'table' AND name = 'memory_chunks_fts'
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : "";
            }
        }
    }

    /**
     * 打开测试专用 SQLite 连接，并确保索引文件父目录存在，便于构造旧索引场景。
     */
    private Connection openConnection(Path indexFile) throws SQLException {
        try {
            Files.createDirectories(indexFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create memory index directory for test.", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + indexFile);
    }

    /**
     * 复用与生产一致的 SHA-256 计算逻辑，确保旧索引场景只因 schema 过旧而不是内容变更触发重建。
     */
    private String sha256(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available in test runtime.", exception);
        }
    }

    /**
     * 固定时钟保证索引记录中的时间字段和测试断言在所有执行环境下都保持稳定。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-04T02:15:30Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 构造最小执行上下文，给 session scope 过滤提供当前内部会话标识。
     */
    private ToolExecutionContext createExecutionContext(String conversationId) {
        return new ToolExecutionContext(
                new InternalUserId("user-1"),
                new InternalConversationId(conversationId),
                new NormalizedDirectMessage("telegram", "external-user-1", "external-conversation-1", "external-message-1", "搜索记忆"),
                new TraceContext("run-1", "telegram", "external-conversation-1", "external-message-1", conversationId, RuntimeObservationMode.OFF),
                workspaceRoot
        );
    }
}
