package com.quashy.openclaw4j.tool.builtin.reminder;

import com.quashy.openclaw4j.reminder.ReminderRecord;
import com.quashy.openclaw4j.reminder.ReminderService;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.model.ToolArgumentException;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolRiskLevel;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供 `reminder.create` 内置工具，使模型可以为当前内部会话登记一次性提醒并得到结构化确认结果。
 */
@Component
public class ReminderCreateTool implements Tool {

    /**
     * 负责 reminder 创建的校验、持久化与观测闭环，避免工具层直接处理业务规则和状态写入。
     */
    private final ReminderService reminderService;

    /**
     * 通过显式注入 reminder 应用服务固定工具职责，让工具专注于协议层参数读取和结果回填。
     */
    public ReminderCreateTool(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 暴露 `reminder.create` 的最小输入 schema，要求模型显式提供提醒文本和带时区的绝对未来时间。
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "reminder.create",
                "为当前内部会话创建一次性文本提醒，并返回提醒标识、调度时间和状态等结构化确认结果。",
                ToolInputSchema.object(
                        Map.of(
                                "text", new ToolInputProperty("string", "提醒正文，必须是非空纯文本。"),
                                "scheduledAt", new ToolInputProperty("string", "带显式时区的 ISO-8601 绝对未来时间，例如 2026-04-05T10:00:00+08:00。")
                        ),
                        List.of("text", "scheduledAt")
                )
        );
    }

    /**
     * 声明 `reminder.create` 会持久化新的提醒事实，但当前仍允许在领域服务校验后直接执行。
     */
    @Override
    public ToolSafetyProfile safetyProfile() {
        return new ToolSafetyProfile(
                ToolRiskLevel.STATE_CHANGING,
                ToolConfirmationPolicy.NEVER,
                ToolArgumentValidatorType.NONE
        );
    }

    /**
     * 读取标准化参数并委托 reminder 服务完成创建，随后把持久化结果转换为稳定的工具载荷。
     */
    @Override
    public Map<String, Object> execute(ToolCallRequest request) {
        if (request.executionContext() == null) {
            throw new ToolArgumentException("reminder.create 需要运行时上下文。", Map.of("field", "executionContext"));
        }
        ReminderRecord reminder = reminderService.createReminder(
                asString(request.arguments().get("text")),
                asString(request.arguments().get("scheduledAt")),
                request.executionContext()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reminderId", reminder.reminderId());
        payload.put("scheduledAt", reminder.scheduledAt().toString());
        payload.put("status", reminder.status().databaseValue());
        payload.put("conversationId", reminder.conversationId().value());
        payload.put("channel", reminder.channel());
        payload.put("reminderPreview", buildPreview(reminder.reminderText()));
        return Map.copyOf(payload);
    }

    /**
     * 把工具参数安全转换为字符串，便于将缺失值留给 reminder 服务做统一参数错误收敛。
     */
    private String asString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    /**
     * 为工具成功结果生成简洁预览，避免完整提醒正文在观测或模型回复阶段无限膨胀。
     */
    private String buildPreview(String reminderText) {
        return reminderText.length() <= 80 ? reminderText : reminderText.substring(0, 80) + "...";
    }
}
