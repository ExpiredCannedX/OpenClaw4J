package com.quashy.openclaw4j.reminder.application;

import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.reminder.infrastructure.sqlite.SqliteReminderRepository;
import com.quashy.openclaw4j.reminder.model.ReminderCreateCommand;
import com.quashy.openclaw4j.reminder.model.ReminderRecord;
import com.quashy.openclaw4j.tool.model.ToolArgumentException;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负责 `reminder.create` 的参数校验、持久化编排和创建观测，让工具层只保留协议适配职责。
 */
@Service
public class ReminderService {

    /**
     * 提供 reminder 事实源的写入能力，使创建工具和后台调度共享同一任务模型。
     */
    private final SqliteReminderRepository reminderRepository;

    /**
     * 负责发布 reminder 创建相关的运行期事件，便于后续追踪创建成功与参数拒绝边界。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 提供统一当前时间，确保“未来时间”校验在生产和测试环境下拥有相同口径。
     */
    private final Clock clock;

    /**
     * 通过显式依赖注入固定 reminder 创建闭环，避免工具类自己管理校验、持久化和观测细节。
     */
    public ReminderService(
            SqliteReminderRepository reminderRepository,
            RuntimeObservationPublisher runtimeObservationPublisher,
            Clock clock
    ) {
        this.reminderRepository = reminderRepository;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
        this.clock = clock;
    }

    /**
     * 校验 reminder 输入并写入一条新的 scheduled 任务；任何参数错误都会被统一转换成 `invalid_arguments`。
     */
    public ReminderRecord createReminder(String rawText, String rawScheduledAt, ToolExecutionContext executionContext) {
        if (executionContext == null) {
            throw new ToolArgumentException("reminder.create 需要运行时上下文。", Map.of("field", "executionContext"));
        }
        String normalizedText = normalizeText(rawText, executionContext);
        OffsetDateTime scheduledAt = parseScheduledAt(rawScheduledAt, executionContext);
        if (!scheduledAt.isAfter(OffsetDateTime.now(clock))) {
            emitRejected(executionContext, "scheduledAt", "scheduledAt 必须是 future 绝对时间。");
            throw new ToolArgumentException("scheduledAt 必须是 future 绝对时间。", Map.of("field", "scheduledAt"));
        }
        ReminderRecord reminder = reminderRepository.create(new ReminderCreateCommand(
                executionContext.conversationId(),
                executionContext.sourceMessage().channel(),
                scheduledAt,
                normalizedText
        ));
        emitAccepted(executionContext, reminder);
        return reminder;
    }

    /**
     * 规范化 reminder 文本并在缺失时给出结构化参数错误，避免空提醒正文进入持久化层。
     */
    private String normalizeText(String rawText, ToolExecutionContext executionContext) {
        if (!StringUtils.hasText(rawText)) {
            emitRejected(executionContext, "text", "text 不能为空。");
            throw new ToolArgumentException("text 不能为空。", Map.of("field", "text"));
        }
        return rawText.trim();
    }

    /**
     * 把工具传入的时间戳解析为带显式时区的绝对时间；未带 offset 的输入会被直接拒绝。
     */
    private OffsetDateTime parseScheduledAt(String rawScheduledAt, ToolExecutionContext executionContext) {
        if (!StringUtils.hasText(rawScheduledAt)) {
            emitRejected(executionContext, "scheduledAt", "scheduledAt 必须提供带显式时区的绝对时间。");
            throw new ToolArgumentException("scheduledAt 必须提供带显式时区的绝对时间。", Map.of("field", "scheduledAt"));
        }
        try {
            return OffsetDateTime.parse(rawScheduledAt);
        } catch (DateTimeParseException exception) {
            emitRejected(executionContext, "scheduledAt", "scheduledAt 必须是带显式时区的 ISO-8601 绝对时间。");
            throw new ToolArgumentException(
                    "scheduledAt 必须是带显式时区的 ISO-8601 绝对时间。",
                    Map.of("field", "scheduledAt")
            );
        }
    }

    /**
     * 发布 reminder 创建成功事件，使运行时间线可以明确看到任务从工具请求进入持久化事实源的时间点。
     */
    private void emitAccepted(ToolExecutionContext executionContext, ReminderRecord reminder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reminderId", reminder.reminderId());
        payload.put("conversationId", reminder.conversationId().value());
        payload.put("channel", reminder.channel());
        payload.put("scheduledAt", reminder.scheduledAt().toString());
        runtimeObservationPublisher.emit(
                executionContext.traceContext(),
                "reminder.create.accepted",
                RuntimeObservationPhase.TOOL,
                RuntimeObservationLevel.INFO,
                "ReminderService",
                Map.copyOf(payload)
        );
    }

    /**
     * 发布参数拒绝事件，让 reminder.create 的失败原因不会只停留在工具错误结果里而缺少运行期轨迹。
     */
    private void emitRejected(ToolExecutionContext executionContext, String field, String message) {
        runtimeObservationPublisher.emit(
                executionContext.traceContext(),
                "reminder.create.rejected",
                RuntimeObservationPhase.TOOL,
                RuntimeObservationLevel.WARN,
                "ReminderService",
                Map.of(
                        "field", field,
                        "reason", message
                )
        );
    }
}
