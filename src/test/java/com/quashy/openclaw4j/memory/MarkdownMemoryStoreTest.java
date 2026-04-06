package com.quashy.openclaw4j.memory;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.conversation.NormalizedDirectMessage;
import com.quashy.openclaw4j.memory.model.MemoryTargetBucket;
import com.quashy.openclaw4j.memory.model.MemoryWriteRequest;
import com.quashy.openclaw4j.memory.model.MemoryWriteResult;
import com.quashy.openclaw4j.memory.model.UserProfileCategory;
import com.quashy.openclaw4j.memory.store.MarkdownMemoryStore;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Markdown memory store 会按目标桶写入文件、补齐 provenance，并在 `USER.md` 中抑制重复条目。
 */
class MarkdownMemoryStoreTest {

    /**
     * 临时工作区目录用于隔离每个测试的 memory 文件布局，避免不同用例互相污染。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * session_log 写入必须在缺失目录下自动创建目标文件，并把最小 provenance 一并落盘。
     */
    @Test
    void shouldCreateMissingSessionLogFileAndPersistProvenance() throws IOException {
        MarkdownMemoryStore store = new MarkdownMemoryStore(workspaceRoot, fixedClock());

        MemoryWriteResult result = store.write(new MemoryWriteRequest(
                MemoryTargetBucket.SESSION_LOG,
                null,
                "需要跟进周一的面试反馈",
                "user_confirmed",
                0.8d,
                createExecutionContext("conversation-1")
        ));

        Path sessionLogFile = workspaceRoot.resolve("memory/2026-04-04.md");
        assertThat(result.relativePath()).isEqualTo("memory/2026-04-04.md");
        assertThat(result.targetBucket()).isEqualTo(MemoryTargetBucket.SESSION_LOG.value());
        assertThat(Files.exists(sessionLogFile)).isTrue();
        assertThat(Files.readString(sessionLogFile))
                .contains("需要跟进周一的面试反馈")
                .contains("written_at: 2026-04-04T10:15:30+08:00")
                .contains("channel: telegram")
                .contains("trigger_reason: user_confirmed")
                .contains("confidence: 0.8")
                .contains("conversation_id: conversation-1");
    }

    /**
     * USER.md 写入必须进入匹配节，并在同节已有相同内容时返回去重结果而不是重复追加。
     */
    @Test
    void shouldAppendUserProfileIntoMatchingSectionAndSuppressDuplicateEntries() throws IOException {
        MarkdownMemoryStore store = new MarkdownMemoryStore(workspaceRoot, fixedClock());
        MemoryWriteRequest request = new MemoryWriteRequest(
                MemoryTargetBucket.USER_PROFILE,
                UserProfileCategory.PREFERENCE,
                "喜欢黑咖啡",
                "user_confirmed",
                null,
                createExecutionContext("conversation-2")
        );

        MemoryWriteResult firstWrite = store.write(request);
        MemoryWriteResult secondWrite = store.write(request);

        String userProfileContent = Files.readString(workspaceRoot.resolve("USER.md"));
        assertThat(firstWrite.duplicateSuppressed()).isFalse();
        assertThat(secondWrite.duplicateSuppressed()).isTrue();
        assertThat(userProfileContent).contains("## 偏好");
        assertThat(countOccurrences(userProfileContent, "喜欢黑咖啡")).isEqualTo(1);
    }

    /**
     * 固定时钟保证写入时间和按日 session 文件名在所有环境下都可稳定断言。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-04T02:15:30Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 构造 memory 写入所需的最小工具执行上下文，确保 provenance 字段来自运行时而不是测试内联字符串。
     */
    private ToolExecutionContext createExecutionContext(String conversationId) {
        return new ToolExecutionContext(
                new InternalUserId("user-1"),
                new InternalConversationId(conversationId),
                new NormalizedDirectMessage("telegram", "external-user-1", "external-conversation-1", "external-message-1", "请记住这个偏好"),
                new TraceContext("run-1", "telegram", "external-conversation-1", "external-message-1", conversationId, RuntimeObservationMode.OFF),
                workspaceRoot
        );
    }

    /**
     * 统计文本中某个片段的出现次数，用于验证 `USER.md` 去重后不会残留重复记忆正文。
     */
    private int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }
}
