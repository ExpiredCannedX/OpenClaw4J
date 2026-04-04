package com.quashy.openclaw4j.tool.builtin.memory;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.memory.LocalMemoryService;
import com.quashy.openclaw4j.memory.index.SqliteMemoryIndexer;
import com.quashy.openclaw4j.memory.store.MarkdownMemoryStore;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import com.quashy.openclaw4j.tool.model.ToolExecutionError;
import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import com.quashy.openclaw4j.tool.model.ToolExecutionSuccess;
import com.quashy.openclaw4j.tool.runtime.DefaultToolExecutor;
import com.quashy.openclaw4j.tool.runtime.LocalToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 memory.search 与 memory.remember 工具会返回结构化结果，并对非法参数保持统一错误协议。
 */
class MemoryToolsTest {

    /**
     * 临时工作区目录用于隔离记忆文件与 SQLite 索引文件，保证工具测试可重复执行。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * remember 工具成功时必须写入目标文件、刷新索引，并返回目标桶与相对路径等结构化元数据。
     */
    @Test
    void shouldRememberIntoCorrectTargetAndReturnStructuredMetadata() throws IOException {
        ToolRegistry toolRegistry = createToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(toolRegistry);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "memory.remember",
                Map.of(
                        "target", "long_term",
                        "content", "用户下周会出差去上海",
                        "reason", "user_confirmed"
                ),
                createExecutionContext("conversation-1")
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionSuccess.class, success -> {
                    assertThat(success.toolName()).isEqualTo("memory.remember");
                    assertThat(success.payload()).containsEntry("targetBucket", "long_term");
                    assertThat(success.payload()).containsEntry("relativePath", "MEMORY.md");
                });
        assertThat(Files.readString(workspaceRoot.resolve("MEMORY.md"))).contains("用户下周会出差去上海");
    }

    /**
     * remember 工具对非法 `USER.md` 类别必须返回统一的 invalid_arguments 错误，而不是静默降级到其他目标桶。
     */
    @Test
    void shouldRejectUnsupportedUserProfileCategory() {
        ToolRegistry toolRegistry = createToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(toolRegistry);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "memory.remember",
                Map.of(
                        "target", "user_profile",
                        "category", "temporary_plan",
                        "content", "下周写完路线图",
                        "reason", "model_inferred"
                ),
                createExecutionContext("conversation-1")
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.toolName()).isEqualTo("memory.remember");
                    assertThat(error.errorCode()).isEqualTo("invalid_arguments");
                    assertThat(error.message()).contains("category");
                });
    }

    /**
     * search 工具在索引无命中时必须返回结构化成功结果和空数组，避免调用方误判为执行失败。
     */
    @Test
    void shouldReturnStructuredEmptyResultWhenSearchFindsNothing() {
        ToolRegistry toolRegistry = createToolRegistry();
        DefaultToolExecutor executor = new DefaultToolExecutor(toolRegistry);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "memory.search",
                Map.of(
                        "query", "不存在的关键词",
                        "scope", "all"
                ),
                createExecutionContext("conversation-1")
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionSuccess.class, success -> {
                    assertThat(success.toolName()).isEqualTo("memory.search");
                    assertThat(success.payload()).containsEntry("query", "不存在的关键词");
                    assertThat(success.payload()).containsEntry("scope", "all");
                    assertThat(success.payload()).containsEntry("matches", List.of());
                });
    }

    /**
     * 构造包含 memory 两个内置工具的注册中心，复用真实 store/index/service 组合验证最小闭环。
     */
    private ToolRegistry createToolRegistry() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        LocalMemoryService memoryService = new LocalMemoryService(
                new MarkdownMemoryStore(workspaceRoot, fixedClock()),
                new SqliteMemoryIndexer(workspaceRoot, workspaceRoot.resolve(".openclaw/memory-index.sqlite"), fixedClock()),
                publisher
        );
        return new LocalToolRegistry(List.of(
                new MemorySearchTool(memoryService),
                new MemoryRememberTool(memoryService)
        ));
    }

    /**
     * 构造工具运行所需的最小执行上下文，确保工具可从运行时读取 provenance 而不是依赖模型透传。
     */
    private ToolExecutionContext createExecutionContext(String conversationId) {
        return new ToolExecutionContext(
                new InternalUserId("user-1"),
                new InternalConversationId(conversationId),
                new NormalizedDirectMessage("telegram", "external-user-1", "external-conversation-1", "external-message-1", "请记住这个事实"),
                new TraceContext("run-1", "telegram", "external-conversation-1", "external-message-1", conversationId, RuntimeObservationMode.OFF),
                workspaceRoot
        );
    }

    /**
     * 固定时钟保证工具写入的 session 文件名和 provenance 时间在断言时保持稳定。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-04T02:15:30Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 记录 memory 工具相关事件，方便后续扩展断言检索、写入和索引观测输出。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 保存测试期间发布的全部观测事件，便于后续在需要时断言 memory 事件边界。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 为单次工具测试返回稳定 trace 上下文，避免事件记录缺少必要的关联标识。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext("generated-run", channel, externalConversationId, externalMessageId, null, RuntimeObservationMode.OFF);
        }

        /**
         * 直接记录事件对象，让 memory service 的埋点在单元测试中也能被完整观察。
         */
        @Override
        public void emit(
                TraceContext traceContext,
                String eventType,
                RuntimeObservationPhase phase,
                RuntimeObservationLevel level,
                String component,
                Map<String, Object> payload,
                Map<String, Object> verbosePayload
        ) {
            events.add(new RuntimeObservationEvent(
                    Instant.parse("2026-04-04T08:00:00Z"),
                    eventType,
                    phase,
                    level,
                    component,
                    traceContext,
                    payload,
                    verbosePayload
            ));
        }
    }
}
