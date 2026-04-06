package com.quashy.openclaw4j.tool.builtin.reminder;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.conversation.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.reminder.application.ReminderService;
import com.quashy.openclaw4j.reminder.infrastructure.sqlite.SqliteReminderRepository;
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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 `reminder.create` 工具会在合法输入下持久化一次性提醒，并对非法时间参数返回统一错误协议。
 */
class ReminderCreateToolTest {

    /**
     * 临时工作区目录用于隔离 reminder SQLite 文件，避免不同测试的提醒状态互相污染。
     */
    @TempDir
    Path workspaceRoot;

    /**
     * 合法提醒创建时必须返回结构化确认结果，并把 reminder 事实写入 SQLite 仓储供后续调度器继续消费。
     */
    @Test
    void shouldPersistReminderAndReturnStructuredMetadataWhenArgumentsAreValid() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        DefaultToolExecutor executor = createExecutor(repository, publisher);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "reminder.create",
                Map.of(
                        "text", "十点提醒我准备周会",
                        "scheduledAt", "2026-04-05T10:05:00+08:00"
                ),
                createExecutionContext()
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionSuccess.class, success -> {
                    assertThat(success.payload()).containsEntry("status", "scheduled");
                    assertThat(success.payload()).containsEntry("conversationId", "conversation-1");
                    assertThat(success.payload()).containsEntry("channel", "telegram");
                    assertThat(success.payload()).containsEntry("scheduledAt", "2026-04-05T10:05+08:00");
                    assertThat(success.payload()).containsEntry("reminderPreview", "十点提醒我准备周会");
                    assertThat(repository.findById((String) success.payload().get("reminderId"))).isPresent();
                });
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.create.accepted");
    }

    /**
     * 缺少显式时区信息的时间戳必须被拒绝为 invalid_arguments，避免工具在 V1 里隐式猜测时区。
     */
    @Test
    void shouldRejectTimestampWithoutExplicitTimezone() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        DefaultToolExecutor executor = createExecutor(repository, publisher);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "reminder.create",
                Map.of(
                        "text", "缺少时区",
                        "scheduledAt", "2026-04-05T10:00:00"
                ),
                createExecutionContext()
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("invalid_arguments");
                    assertThat(error.message()).contains("scheduledAt");
                });
        assertThat(repository.claimDueReminders(java.time.OffsetDateTime.parse("2026-04-05T12:00:00+08:00"), 10)).isEmpty();
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.create.rejected");
    }

    /**
     * 已经过去的时间点必须被拒绝，避免 reminder.create 变成“立即补发”的隐式工具。
     */
    @Test
    void shouldRejectPastTimestamp() {
        RecordingRuntimeObservationPublisher publisher = new RecordingRuntimeObservationPublisher();
        SqliteReminderRepository repository = new SqliteReminderRepository(workspaceRoot.resolve(".openclaw/reminders.sqlite"), fixedClock());
        DefaultToolExecutor executor = createExecutor(repository, publisher);

        ToolExecutionResult result = executor.execute(new ToolCallRequest(
                "reminder.create",
                Map.of(
                        "text", "已经过去",
                        "scheduledAt", "2026-04-05T09:59:59+08:00"
                ),
                createExecutionContext()
        ));

        assertThat(result)
                .isInstanceOfSatisfying(ToolExecutionError.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("invalid_arguments");
                    assertThat(error.message()).contains("future");
                });
        assertThat(repository.claimDueReminders(java.time.OffsetDateTime.parse("2026-04-05T12:00:00+08:00"), 10)).isEmpty();
        assertThat(publisher.events)
                .extracting(RuntimeObservationEvent::eventType)
                .contains("reminder.create.rejected");
    }

    /**
     * 构造只注册 `reminder.create` 的工具执行器，保证测试直接覆盖真实工具协议和错误收敛路径。
     */
    private DefaultToolExecutor createExecutor(SqliteReminderRepository repository, RecordingRuntimeObservationPublisher publisher) {
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(
                new ReminderCreateTool(new ReminderService(repository, publisher, fixedClock()))
        ));
        return new DefaultToolExecutor(toolRegistry);
    }

    /**
     * 构造 reminder.create 所需的最小运行时上下文，使工具能从系统事实读取当前会话和渠道信息。
     */
    private ToolExecutionContext createExecutionContext() {
        return new ToolExecutionContext(
                new InternalUserId("user-1"),
                new InternalConversationId("conversation-1"),
                new NormalizedDirectMessage("telegram", "external-user-1", "2001", "msg-1", "请十点提醒我"),
                new TraceContext("run-1", "telegram", "2001", "msg-1", "conversation-1", RuntimeObservationMode.OFF),
                workspaceRoot
        );
    }

    /**
     * 固定时钟保证“未来时间”和“过去时间”的边界在任何执行环境下都稳定可重复。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 记录 reminder 工具相关事件，便于断言创建成功和参数拒绝都会产出明确的运行期观测。
     */
    private static final class RecordingRuntimeObservationPublisher implements RuntimeObservationPublisher {

        /**
         * 保存测试期间发布的全部事件，供断言 reminder.create 的关键行为边界。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 返回稳定 trace 上下文，确保测试无需依赖外部发布器生成策略。
         */
        @Override
        public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
            return new TraceContext("generated-run", channel, externalConversationId, externalMessageId, null, RuntimeObservationMode.OFF);
        }

        /**
         * 记录只包含摘要字段的事件，满足当前 reminder.create 测试对事件类型的断言需求。
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
                    Instant.parse("2026-04-05T02:00:00Z"),
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
