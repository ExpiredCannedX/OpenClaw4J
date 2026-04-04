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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 SQLite memory indexer 会为工作区记忆文件建立 FTS 索引，并按会话上下文过滤 session 结果。
 */
class SqliteMemoryIndexerTest {

    /**
     * 临时工作区目录用于生成独立 SQLite 文件和 Markdown 事实源，保证测试之间互不干扰。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * 启动刷新后应能搜索到刚建立的 Markdown 记忆块，并返回结构化文件路径、行号和目标桶信息。
     */
    @Test
    void shouldIndexWorkspaceMemoryFilesAndReturnStructuredMatches() throws IOException {
        Files.writeString(workspaceRoot.resolve("USER.md"), """
                # 用户画像

                ## 偏好

                - 喜欢黑咖啡
                  - written_at: 2026-04-04T10:15:30+08:00
                  - channel: telegram
                  - trigger_reason: user_confirmed
                """);
        SqliteMemoryIndexer indexer = new SqliteMemoryIndexer(
                workspaceRoot,
                workspaceRoot.resolve(".openclaw/memory-index.sqlite"),
                fixedClock()
        );

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
                    assertThat(match.lineStart()).isGreaterThan(0);
                    assertThat(match.lineEnd()).isGreaterThanOrEqualTo(match.lineStart());
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
        SqliteMemoryIndexer indexer = new SqliteMemoryIndexer(
                workspaceRoot,
                workspaceRoot.resolve(".openclaw/memory-index.sqlite"),
                fixedClock()
        );

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
