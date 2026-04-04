package com.quashy.openclaw4j.memory.index;

import com.quashy.openclaw4j.memory.model.MemorySearchMatch;
import com.quashy.openclaw4j.memory.model.MemorySearchRequest;
import com.quashy.openclaw4j.memory.model.MemorySearchScope;
import com.quashy.openclaw4j.memory.model.MemoryTargetBucket;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 负责把 Markdown 事实源同步到本地 SQLite FTS 索引，并提供按范围过滤的最小全文检索能力。
 */
public class SqliteMemoryIndexer {

    /**
     * 指向当前索引所绑定的 workspace 根目录，用于扫描 `USER.md`、`MEMORY.md` 与 `memory/*.md`。
     */
    private final Path workspaceRoot;

    /**
     * 指向本地 SQLite 单文件索引路径，所有 memory 检索与哈希追踪都围绕该文件展开。
     */
    private final Path indexFile;

    /**
     * 提供统一时间源，用于记录索引更新时间并保持测试断言稳定。
     */
    private final Clock clock;

    /**
     * 通过显式注入 workspace 和索引文件路径固定索引边界，避免组件依赖外部静态配置。
     */
    public SqliteMemoryIndexer(Path workspaceRoot, Path indexFile, Clock clock) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.indexFile = indexFile.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * 扫描所有受支持的 memory 文件，并仅对新增或内容变更的文件刷新索引条目。
     */
    public void refreshChangedFiles() {
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            for (Path relativePath : listSupportedFiles()) {
                Path filePath = workspaceRoot.resolve(relativePath);
                if (!Files.exists(filePath)) {
                    continue;
                }
                String content = Files.readString(filePath);
                String contentHash = sha256(content);
                if (!contentHash.equals(findStoredHash(connection, normalizeRelativePath(relativePath)))) {
                    rebuildFile(connection, relativePath, content, contentHash);
                }
            }
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed to refresh memory index.", exception);
        }
    }

    /**
     * 同步重建单个已知文件的索引，用于 remember 成功写入后的最小闭环刷新。
     */
    public void refreshFile(String relativePath) {
        Path normalizedRelativePath = Path.of(relativePath.replace('\\', '/'));
        Path filePath = workspaceRoot.resolve(normalizedRelativePath);
        if (!Files.exists(filePath)) {
            return;
        }
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            String content = Files.readString(filePath);
            rebuildFile(connection, normalizedRelativePath, content, sha256(content));
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed to refresh memory index file: " + relativePath, exception);
        }
    }

    /**
     * 在本地 FTS 索引上执行查询，并按 scope 过滤目标桶或当前会话相关的 session 日志块。
     */
    public List<MemorySearchMatch> search(MemorySearchRequest request) {
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(buildSearchSql(request.scope()))) {
                bindSearchParameters(statement, request);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<MemorySearchMatch> matches = new ArrayList<>();
                    while (resultSet.next()) {
                        matches.add(new MemorySearchMatch(
                                resultSet.getString("relative_path"),
                                resultSet.getString("target_bucket"),
                                resultSet.getInt("line_start"),
                                resultSet.getInt("line_end"),
                                resultSet.getString("content"),
                                resultSet.getDouble("score")
                        ));
                    }
                    return matches;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to search memory index.", exception);
        }
    }

    /**
     * 打开 SQLite 连接并确保索引目录存在，使首次启动时不需要额外的手工初始化步骤。
     */
    private Connection openConnection() throws SQLException {
        try {
            Files.createDirectories(indexFile.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create memory index directory.", exception);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + indexFile);
    }

    /**
     * 创建 memory 文件元信息表、文本块表和 FTS5 虚表，确保索引初始化具备自包含能力。
     */
    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
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
        }
    }

    /**
     * 返回 memory V1 受支持的全部事实源文件列表，并忽略缺失文件以保持启动期的容错能力。
     */
    private List<Path> listSupportedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(Path.of("USER.md"));
        files.add(Path.of("MEMORY.md"));
        Path memoryDirectory = workspaceRoot.resolve("memory");
        if (Files.isDirectory(memoryDirectory)) {
            try (var stream = Files.list(memoryDirectory)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted()
                        .map(workspaceRoot::relativize)
                        .forEach(files::add);
            }
        }
        return files;
    }

    /**
     * 查询指定文件当前记录的哈希值，用于判断是否需要在启动期重建索引。
     */
    private String findStoredHash(Connection connection, String relativePath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT content_hash FROM memory_files WHERE relative_path = ?"
        )) {
            statement.setString(1, relativePath);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("content_hash") : null;
            }
        }
    }

    /**
     * 删除指定文件的旧块并重建当前索引记录，保证单文件刷新时不会残留过期命中。
     */
    private void rebuildFile(Connection connection, Path relativePath, String content, String contentHash) throws SQLException {
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        deleteExistingChunks(connection, normalizedRelativePath);
        for (Chunk chunk : chunkContent(relativePath, content)) {
            long chunkId = insertChunk(connection, normalizedRelativePath, chunk);
            insertChunkFts(connection, chunkId, chunk);
        }
        upsertFileRecord(connection, normalizedRelativePath, contentHash);
    }

    /**
     * 删除指定文件既有的普通块和 FTS 块，避免索引在内容变更后返回陈旧结果。
     */
    private void deleteExistingChunks(Connection connection, String relativePath) throws SQLException {
        List<Long> chunkIds = new ArrayList<>();
        try (PreparedStatement selectStatement = connection.prepareStatement(
                "SELECT id FROM memory_chunks WHERE relative_path = ?"
        )) {
            selectStatement.setString(1, relativePath);
            try (ResultSet resultSet = selectStatement.executeQuery()) {
                while (resultSet.next()) {
                    chunkIds.add(resultSet.getLong("id"));
                }
            }
        }
        try (PreparedStatement deleteFts = connection.prepareStatement("DELETE FROM memory_chunks_fts WHERE rowid = ?")) {
            for (Long chunkId : chunkIds) {
                deleteFts.setLong(1, chunkId);
                deleteFts.addBatch();
            }
            deleteFts.executeBatch();
        }
        try (PreparedStatement deleteChunks = connection.prepareStatement(
                "DELETE FROM memory_chunks WHERE relative_path = ?"
        )) {
            deleteChunks.setString(1, relativePath);
            deleteChunks.executeUpdate();
        }
    }

    /**
     * 把单个文本块写入普通表，并返回分配的 chunk 主键供 FTS 表复用。
     */
    private long insertChunk(Connection connection, String relativePath, Chunk chunk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_chunks(relative_path, target_bucket, conversation_id, line_start, line_end, content, provenance_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, relativePath);
            statement.setString(2, chunk.targetBucket());
            statement.setString(3, chunk.conversationId());
            statement.setInt(4, chunk.lineStart());
            statement.setInt(5, chunk.lineEnd());
            statement.setString(6, chunk.content());
            statement.setString(7, chunk.provenanceSummary());
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("Failed to allocate memory chunk id.");
                }
                return generatedKeys.getLong(1);
            }
        }
    }

    /**
     * 把普通块内容同步写入 FTS5 虚表，确保查询阶段能够直接使用 bm25 排序。
     */
    private void insertChunkFts(Connection connection, long chunkId, Chunk chunk) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_chunks_fts(rowid, content, provenance_summary)
                VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, chunkId);
            statement.setString(2, chunk.content());
            statement.setString(3, chunk.provenanceSummary());
            statement.executeUpdate();
        }
    }

    /**
     * 更新文件哈希与索引时间，供启动期后续判断该文件是否需要再次刷新。
     */
    private void upsertFileRecord(Connection connection, String relativePath, String contentHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_files(relative_path, content_hash, updated_at, indexed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(relative_path) DO UPDATE SET
                    content_hash = excluded.content_hash,
                    updated_at = excluded.updated_at,
                    indexed_at = excluded.indexed_at
                """)) {
            String now = OffsetDateTime.now(clock).toString();
            statement.setString(1, relativePath);
            statement.setString(2, contentHash);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
        }
    }

    /**
     * 按空行分块解析 Markdown 内容，并把最近的标题信息前置到块文本中，以提升检索结果可读性。
     */
    private List<Chunk> chunkContent(Path relativePath, String content) {
        String[] lines = content.split("\\R", -1);
        List<Chunk> chunks = new ArrayList<>();
        String currentSection = "";
        int blockStart = -1;
        List<String> blockLines = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("#")) {
                currentSection = line;
            }
            if (line.isBlank()) {
                if (!blockLines.isEmpty()) {
                    addChunk(relativePath, currentSection, blockStart, index, blockLines, chunks);
                    blockLines = new ArrayList<>();
                    blockStart = -1;
                }
                continue;
            }
            if (blockStart < 0) {
                blockStart = index + 1;
            }
            blockLines.add(line);
        }
        if (!blockLines.isEmpty()) {
            addChunk(relativePath, currentSection, blockStart, lines.length, blockLines, chunks);
        }
        return chunks;
    }

    /**
     * 把一个已解析的非空 Markdown 块转换成可索引 chunk，并抽取会话 provenance 以支撑 session scope 过滤。
     */
    private void addChunk(
            Path relativePath,
            String currentSection,
            int lineStart,
            int lineEnd,
            List<String> blockLines,
            List<Chunk> chunks
    ) {
        String joinedBlock = String.join(System.lineSeparator(), blockLines);
        if (joinedBlock.startsWith("#")) {
            return;
        }
        String chunkContent = currentSection.isBlank() ? joinedBlock : currentSection + System.lineSeparator() + joinedBlock;
        chunks.add(new Chunk(
                determineTargetBucket(relativePath).value(),
                lineStart,
                lineEnd,
                chunkContent,
                extractConversationId(blockLines),
                extractProvenanceSummary(blockLines)
        ));
    }

    /**
     * 根据相对路径判断块所属的目标桶，保持索引层与存储层文件归属规则一致。
     */
    private MemoryTargetBucket determineTargetBucket(Path relativePath) {
        String normalizedPath = normalizeRelativePath(relativePath);
        if ("USER.md".equals(normalizedPath)) {
            return MemoryTargetBucket.USER_PROFILE;
        }
        if ("MEMORY.md".equals(normalizedPath)) {
            return MemoryTargetBucket.LONG_TERM;
        }
        return MemoryTargetBucket.SESSION_LOG;
    }

    /**
     * 从块内的 provenance 行中提取 conversation_id，供 session scope 查询做精确过滤。
     */
    private String extractConversationId(List<String> blockLines) {
        return blockLines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("- conversation_id:"))
                .map(line -> line.substring("- conversation_id:".length()).trim())
                .findFirst()
                .orElse(null);
    }

    /**
     * 从块内提取简洁 provenance 摘要，使搜索结果在需要时能够附带来源信息而不暴露整文件。
     */
    private String extractProvenanceSummary(List<String> blockLines) {
        return blockLines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("- written_at:")
                        || line.startsWith("- channel:")
                        || line.startsWith("- trigger_reason:")
                        || line.startsWith("- confidence:")
                        || line.startsWith("- conversation_id:"))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    /**
     * 根据搜索范围构造 SQL 语句，确保 `session` 查询只命中当前会话相关块，而其他范围保持显式桶过滤。
     */
    private String buildSearchSql(MemorySearchScope scope) {
        return switch (scope) {
            case ALL -> """
                    SELECT relative_path, target_bucket, line_start, line_end, content, 0.0 AS score
                    FROM memory_chunks
                    WHERE content LIKE ? OR provenance_summary LIKE ?
                    ORDER BY relative_path, line_start
                    LIMIT 10
                    """;
            case USER_PROFILE, LONG_TERM -> """
                    SELECT relative_path, target_bucket, line_start, line_end, content, 0.0 AS score
                    FROM memory_chunks
                    WHERE target_bucket = ? AND (content LIKE ? OR provenance_summary LIKE ?)
                    ORDER BY relative_path, line_start
                    LIMIT 10
                    """;
            case SESSION -> """
                    SELECT relative_path, target_bucket, line_start, line_end, content, 0.0 AS score
                    FROM memory_chunks
                    WHERE target_bucket = ?
                      AND conversation_id = ?
                      AND (content LIKE ? OR provenance_summary LIKE ?)
                    ORDER BY relative_path, line_start
                    LIMIT 10
                    """;
        };
    }

    /**
     * 按 scope 为查询绑定参数，避免调用方手写不同 SQL 参数顺序。
     */
    private void bindSearchParameters(PreparedStatement statement, MemorySearchRequest request) throws SQLException {
        String likePattern = "%" + request.query() + "%";
        if (request.scope() == MemorySearchScope.USER_PROFILE) {
            statement.setString(1, MemoryTargetBucket.USER_PROFILE.value());
            statement.setString(2, likePattern);
            statement.setString(3, likePattern);
        } else if (request.scope() == MemorySearchScope.LONG_TERM) {
            statement.setString(1, MemoryTargetBucket.LONG_TERM.value());
            statement.setString(2, likePattern);
            statement.setString(3, likePattern);
        } else if (request.scope() == MemorySearchScope.SESSION) {
            statement.setString(1, MemoryTargetBucket.SESSION_LOG.value());
            statement.setString(2, request.executionContext().conversationId().value());
            statement.setString(3, likePattern);
            statement.setString(4, likePattern);
        } else {
            statement.setString(1, likePattern);
            statement.setString(2, likePattern);
        }
    }

    /**
     * 计算文件内容的 SHA-256 哈希，作为启动期判断文件是否变化的稳定依据。
     */
    private String sha256(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    /**
     * 把相对路径规范化为正斜杠形式，避免不同平台路径分隔符破坏索引键一致性。
     */
    private String normalizeRelativePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    /**
     * 表示一个已完成分类和 provenance 摘要提取的索引块，避免在入库方法之间传递松散参数。
     */
    private record Chunk(
            /**
             * 标识该块所属目标桶，用于 search scope 过滤和结构化结果回填。
             */
            String targetBucket,
            /**
             * 标识块的起始行号，供搜索结果定位原文件位置。
             */
            int lineStart,
            /**
             * 标识块的结束行号，供调用方理解片段覆盖范围。
             */
            int lineEnd,
            /**
             * 承载真正参与全文检索的块文本，包含最近节标题以提升可读性。
             */
            String content,
            /**
             * 保存块中提取出的当前会话标识，仅 session 日志通常会携带该字段。
             */
            String conversationId,
            /**
             * 保存块内简化后的 provenance 摘要，供后续扩展检索字段或调试输出复用。
             */
            String provenanceSummary
    ) {
    }
}
