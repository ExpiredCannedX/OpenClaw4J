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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 负责把 Markdown 事实源同步到本地 SQLite 派生索引，并以 trigram FTS + 受控短词补偿提供稳定的 memory 检索语义。
 */
public class SqliteMemoryIndexer {

    /**
     * 标识当前 memory 派生索引的 schema version，用于判定旧索引是否需要整体重建。
     */
    private static final int INDEX_SCHEMA_VERSION = 2;

    /**
     * 约束单次检索最多返回的命中数，避免工具结果在 prompt 中无限膨胀。
     */
    private static final int RESULT_LIMIT = 10;

    /**
     * 让正文列在 BM25 排序里明显高于 provenance 摘要，避免元数据文本压过真正的记忆内容。
     */
    private static final double CONTENT_BM25_WEIGHT = 10.0;

    /**
     * 让 provenance 摘要仍参与相关度计算，但权重低于正文以保持排序直觉一致。
     */
    private static final double PROVENANCE_BM25_WEIGHT = 1.0;

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
     * 扫描所有受支持的 memory 文件，并仅对新增、内容变更或 schema 迁移后的文件刷新索引条目。
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
     * 在本地 SQLite 索引上执行查询，并根据长词 `MATCH` 与短词补偿规则返回稳定排序的结构化结果。
     */
    public List<MemorySearchMatch> search(MemorySearchRequest request) {
        try (Connection connection = openConnection()) {
            ensureSchema(connection);
            CompiledSearchQuery compiledSearchQuery = compileSearchQuery(request.query());
            List<SearchCandidate> candidates = compiledSearchQuery.usesMatch()
                    ? searchWithMatch(connection, request, compiledSearchQuery)
                    : searchWithLikeFallback(connection, request, compiledSearchQuery.shortTerms());
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(SearchCandidate::score).reversed()
                            .thenComparing(SearchCandidate::relativePath)
                            .thenComparingInt(SearchCandidate::lineStart))
                    .limit(RESULT_LIMIT)
                    .map(this::toSearchMatch)
                    .toList();
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
     * 检查当前索引 schema 是否与预期版本一致；若不一致则整体重建派生结构，再补齐所需表定义。
     */
    private void ensureSchema(Connection connection) throws SQLException {
        if (requiresSchemaRebuild(connection)) {
            rebuildDerivedSchema(connection);
        }
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
                        content_rowid='id',
                        tokenize='trigram'
                    )
                    """);
            statement.execute("PRAGMA user_version = " + INDEX_SCHEMA_VERSION);
        }
    }

    /**
     * 判断当前 SQLite 文件是否仍是兼容的 memory 搜索 schema，避免旧 tokenizer 或旧版本索引继续提供服务。
     */
    private boolean requiresSchemaRebuild(Connection connection) throws SQLException {
        if (readSchemaVersion(connection) != INDEX_SCHEMA_VERSION) {
            return true;
        }
        if (readTableDefinition(connection, "memory_files").isBlank()) {
            return true;
        }
        if (readTableDefinition(connection, "memory_chunks").isBlank()) {
            return true;
        }
        return !isExpectedFtsDefinition(readTableDefinition(connection, "memory_chunks_fts"));
    }

    /**
     * 删除全部派生索引结构，让旧 tokenizer 和旧版本元数据不会在升级后残留陈旧行为。
     */
    private void rebuildDerivedSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS memory_chunks_fts");
            statement.execute("DROP TABLE IF EXISTS memory_chunks");
            statement.execute("DROP TABLE IF EXISTS memory_files");
            statement.execute("PRAGMA user_version = 0");
        }
    }

    /**
     * 读取 SQLite `user_version` 作为索引 schema version，避免为派生元数据再引入额外业务表。
     */
    private int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    /**
     * 读取指定表或虚表的建表 SQL，用于判定 FTS 定义是否仍与当前代码期望一致。
     */
    private String readTableDefinition(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sql
                FROM sqlite_master
                WHERE name = ?
                """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("sql") : "";
            }
        }
    }

    /**
     * 验证 FTS 虚表是否仍使用预期的 trigram、外部内容表和 rowid 绑定，避免部分升级留下隐式行为。
     */
    private boolean isExpectedFtsDefinition(String ftsDefinition) {
        String normalizedDefinition = normalizeSql(ftsDefinition);
        return normalizedDefinition.contains("tokenize='trigram'")
                && normalizedDefinition.contains("content='memory_chunks'")
                && normalizedDefinition.contains("content_rowid='id'");
    }

    /**
     * 统一压缩 SQL 中的多余空白，避免 sqlite_master 返回格式差异导致定义比较出现误判。
     */
    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
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
     * 把普通块内容同步写入 trigram FTS 虚表，使全文检索始终与普通结构化字段保持一致。
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
     * 把原始查询归一化为稳定的 term 列表，并区分可走 trigram `MATCH` 的长词与只能补偿的短词。
     */
    private CompiledSearchQuery compileSearchQuery(String rawQuery) {
        List<String> normalizedTerms = Arrays.stream(rawQuery.strip().split("\\s+"))
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
        List<String> longTerms = new ArrayList<>();
        List<String> shortTerms = new ArrayList<>();
        for (String term : normalizedTerms) {
            if (term.codePointCount(0, term.length()) >= 3) {
                longTerms.add(term);
            } else {
                shortTerms.add(term);
            }
        }
        String matchExpression = longTerms.isEmpty()
                ? null
                : longTerms.stream().map(this::escapeMatchPhrase).collect(Collectors.joining(" AND "));
        return new CompiledSearchQuery(String.join(" ", normalizedTerms), longTerms, shortTerms, matchExpression);
    }

    /**
     * 使用 FTS5 `MATCH` 执行主检索，并在 SQL 侧保留 scope 与短词补偿过滤，确保候选集尽量收敛。
     */
    private List<SearchCandidate> searchWithMatch(
            Connection connection,
            MemorySearchRequest request,
            CompiledSearchQuery compiledSearchQuery
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                buildMatchSearchSql(request.scope(), compiledSearchQuery.shortTerms().size())
        )) {
            bindMatchSearchParameters(statement, request, compiledSearchQuery);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SearchCandidate> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    candidates.add(new SearchCandidate(
                            resultSet.getString("relative_path"),
                            resultSet.getString("target_bucket"),
                            resultSet.getInt("line_start"),
                            resultSet.getInt("line_end"),
                            resultSet.getString("content"),
                            toPositiveFtsScore(resultSet.getDouble("raw_score"))
                    ));
                }
                return candidates;
            }
        }
    }

    /**
     * 在所有查询项都过短时退回结构化 `LIKE` 路径，并使用受控子串分数保持结果顺序稳定可解释。
     */
    private List<SearchCandidate> searchWithLikeFallback(
            Connection connection,
            MemorySearchRequest request,
            List<String> shortTerms
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                buildLikeFallbackSql(request.scope(), shortTerms.size())
        )) {
            bindLikeFallbackParameters(statement, request, shortTerms);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SearchCandidate> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    String content = resultSet.getString("content");
                    String provenanceSummary = resultSet.getString("provenance_summary");
                    candidates.add(new SearchCandidate(
                            resultSet.getString("relative_path"),
                            resultSet.getString("target_bucket"),
                            resultSet.getInt("line_start"),
                            resultSet.getInt("line_end"),
                            content,
                            computeLikeFallbackScore(shortTerms, content, provenanceSummary)
                    ));
                }
                return candidates;
            }
        }
    }

    /**
     * 构造 FTS 主查询 SQL，让全文匹配只负责找文本，而 scope 继续由结构化字段精确约束。
     */
    private String buildMatchSearchSql(MemorySearchScope scope, int shortTermCount) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    memory_chunks.relative_path,
                    memory_chunks.target_bucket,
                    memory_chunks.line_start,
                    memory_chunks.line_end,
                    memory_chunks.content,
                    bm25(memory_chunks_fts, %s, %s) AS raw_score
                FROM memory_chunks_fts
                JOIN memory_chunks ON memory_chunks.id = memory_chunks_fts.rowid
                WHERE memory_chunks_fts MATCH ?
                """.formatted(CONTENT_BM25_WEIGHT, PROVENANCE_BM25_WEIGHT));
        appendScopePredicate(sql, scope);
        appendLikePredicates(sql, shortTermCount, "memory_chunks.content", "memory_chunks.provenance_summary");
        sql.append("""

                ORDER BY raw_score ASC, memory_chunks.relative_path ASC, memory_chunks.line_start ASC
                """);
        return sql.toString();
    }

    /**
     * 构造全短词 fallback SQL，保持与 FTS 路径一致的 scope 约束和结果字段结构。
     */
    private String buildLikeFallbackSql(MemorySearchScope scope, int shortTermCount) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    memory_chunks.relative_path,
                    memory_chunks.target_bucket,
                    memory_chunks.line_start,
                    memory_chunks.line_end,
                    memory_chunks.content,
                    memory_chunks.provenance_summary
                FROM memory_chunks
                WHERE 1 = 1
                """);
        appendScopePredicate(sql, scope);
        appendLikePredicates(sql, shortTermCount, "memory_chunks.content", "memory_chunks.provenance_summary");
        sql.append("""

                ORDER BY memory_chunks.relative_path ASC, memory_chunks.line_start ASC
                """);
        return sql.toString();
    }

    /**
     * 根据 scope 把结构化业务过滤拼进 SQL，避免把 target bucket 或 conversation 语义塞进全文索引文本。
     */
    private void appendScopePredicate(StringBuilder sql, MemorySearchScope scope) {
        switch (scope) {
            case USER_PROFILE -> sql.append("\nAND memory_chunks.target_bucket = ?");
            case LONG_TERM -> sql.append("\nAND memory_chunks.target_bucket = ?");
            case SESSION -> sql.append("""

                    AND memory_chunks.target_bucket = ?
                    AND memory_chunks.conversation_id = ?
                    """);
            case ALL -> {
            }
        }
    }

    /**
     * 为每个短词追加受控 `LIKE` 条件，并显式声明转义字符以避免 `%`、`_` 被误解释成通配符。
     */
    private void appendLikePredicates(StringBuilder sql, int shortTermCount, String contentColumn, String provenanceColumn) {
        for (int index = 0; index < shortTermCount; index++) {
            sql.append("""

                    AND (
                        %s LIKE ? ESCAPE '!'
                        OR %s LIKE ? ESCAPE '!'
                    )
                    """.formatted(contentColumn, provenanceColumn));
        }
    }

    /**
     * 绑定 FTS 主查询的参数顺序，确保 `MATCH`、scope 和短词补偿条件之间不会发生错位。
     */
    private void bindMatchSearchParameters(
            PreparedStatement statement,
            MemorySearchRequest request,
            CompiledSearchQuery compiledSearchQuery
    ) throws SQLException {
        int parameterIndex = 1;
        statement.setString(parameterIndex++, compiledSearchQuery.matchExpression());
        parameterIndex = bindScopeParameters(statement, parameterIndex, request);
        bindShortTermLikeParameters(statement, parameterIndex, compiledSearchQuery.shortTerms());
    }

    /**
     * 绑定全短词 fallback 查询参数，复用与主路径一致的 scope 参数顺序与短词转义规则。
     */
    private void bindLikeFallbackParameters(
            PreparedStatement statement,
            MemorySearchRequest request,
            List<String> shortTerms
    ) throws SQLException {
        int parameterIndex = bindScopeParameters(statement, 1, request);
        bindShortTermLikeParameters(statement, parameterIndex, shortTerms);
    }

    /**
     * 按 scope 为查询绑定结构化过滤参数，避免调用方手写不同 SQL 参数顺序。
     */
    private int bindScopeParameters(PreparedStatement statement, int startIndex, MemorySearchRequest request) throws SQLException {
        int parameterIndex = startIndex;
        if (request.scope() == MemorySearchScope.USER_PROFILE) {
            statement.setString(parameterIndex++, MemoryTargetBucket.USER_PROFILE.value());
        } else if (request.scope() == MemorySearchScope.LONG_TERM) {
            statement.setString(parameterIndex++, MemoryTargetBucket.LONG_TERM.value());
        } else if (request.scope() == MemorySearchScope.SESSION) {
            statement.setString(parameterIndex++, MemoryTargetBucket.SESSION_LOG.value());
            statement.setString(parameterIndex++, request.executionContext().conversationId().value());
        }
        return parameterIndex;
    }

    /**
     * 为短词补偿条件绑定 `%term%` 模式，并对通配符做转义，避免 fallback 路径退化成不受控模糊匹配。
     */
    private void bindShortTermLikeParameters(PreparedStatement statement, int startIndex, List<String> shortTerms) throws SQLException {
        int parameterIndex = startIndex;
        for (String shortTerm : shortTerms) {
            String likePattern = buildEscapedLikePattern(shortTerm);
            statement.setString(parameterIndex++, likePattern);
            statement.setString(parameterIndex++, likePattern);
        }
    }

    /**
     * 把短词包装为安全的 `LIKE` 子串模式，并转义 SQLite 会识别的通配符字符。
     */
    private String buildEscapedLikePattern(String queryTerm) {
        String escapedTerm = queryTerm
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escapedTerm + "%";
    }

    /**
     * 把原始查询项包装成安全的 FTS phrase，避免引号和保留字符破坏 `MATCH` 语法。
     */
    private String escapeMatchPhrase(String queryTerm) {
        return "\"" + queryTerm.replace("\"", "\"\"") + "\"";
    }

    /**
     * 把 SQLite 原始 BM25 值转换为“越大越相关”的正向分数，保持工具对外语义直观稳定。
     */
    private double toPositiveFtsScore(double rawScore) {
        double invertedScore = -rawScore;
        if (invertedScore > 0.0d) {
            return invertedScore;
        }
        return 1.0d / (1.0d + Math.max(rawScore, 0.0d));
    }

    /**
     * 为全短词 fallback 结果生成稳定的启发式分数，使其即使不走 BM25 也能保持非零相关度语义。
     */
    private double computeLikeFallbackScore(List<String> shortTerms, String content, String provenanceSummary) {
        double score = 0.0d;
        for (String shortTerm : shortTerms) {
            if (content.contains(shortTerm)) {
                score += 1.0d;
            }
            if (!provenanceSummary.isBlank() && provenanceSummary.contains(shortTerm)) {
                score += 0.25d;
            }
        }
        return score;
    }

    /**
     * 把内部候选对象压平为对外暴露的结构化命中，避免让工具层依赖索引器内部排序细节。
     */
    private MemorySearchMatch toSearchMatch(SearchCandidate candidate) {
        return new MemorySearchMatch(
                candidate.relativePath(),
                candidate.targetBucket(),
                candidate.lineStart(),
                candidate.lineEnd(),
                candidate.previewSnippet(),
                candidate.score()
        );
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
     * 表示一次已归一化并完成查询路径判定的搜索请求，避免在 SQL 组装阶段重复解析原始字符串。
     */
    private record CompiledSearchQuery(
            /**
             * 保存去重并压缩空白后的规范化查询文本，便于调试和后续扩展观察输出。
             */
            String normalizedQuery,
            /**
             * 保存可直接进入 trigram `MATCH` 的长查询项列表，确保主检索路径只处理可命中的 term。
             */
            List<String> longTerms,
            /**
             * 保存长度不足 trigram 下界的短查询项列表，供 `LIKE` 补偿路径复用。
             */
            List<String> shortTerms,
            /**
             * 保存已经完成安全转义的 FTS 表达式，避免 SQL 绑定阶段重复拼装 phrase。
             */
            String matchExpression
    ) {

        /**
         * 返回当前查询是否包含可走 FTS 主路径的长词，供检索执行阶段选择 SQL 模板。
         */
        private boolean usesMatch() {
            return matchExpression != null && !matchExpression.isBlank();
        }
    }

    /**
     * 表示一个已完成 score 计算但尚未转换为对外 record 的检索候选，用于统一排序与限流。
     */
    private record SearchCandidate(
            /**
             * 标识命中块所在的相对文件路径，便于最终结果定位事实源。
             */
            String relativePath,
            /**
             * 标识命中块所属的目标桶值，保证工具层仍可区分画像、长期记忆和会话日志。
             */
            String targetBucket,
            /**
             * 标识命中块起始行号，供调用方和测试验证片段位置。
             */
            int lineStart,
            /**
             * 标识命中块结束行号，帮助调用方理解结果覆盖范围。
             */
            int lineEnd,
            /**
             * 保存适合直接暴露给模型的片段预览，而不是额外保留内部 provenance 字段。
             */
            String previewSnippet,
            /**
             * 保存已经转换为正向语义的相关度分数，供统一排序和对外回填。
             */
            double score
    ) {
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
             * 承载当前关键词检索复用的块文本，并包含最近节标题以提升结果可读性。
             */
            String content,
            /**
             * 保存块中提取出的当前会话标识，仅 session 日志通常会携带该字段。
             */
            String conversationId,
            /**
             * 保存块内简化后的 provenance 摘要，供短词补偿与调试输出复用。
             */
            String provenanceSummary
    ) {
    }
}
