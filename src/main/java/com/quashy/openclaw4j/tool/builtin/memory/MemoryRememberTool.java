package com.quashy.openclaw4j.tool.builtin.memory;

import com.quashy.openclaw4j.memory.LocalMemoryService;
import com.quashy.openclaw4j.memory.model.MemoryWriteResult;
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
 * 提供 `memory.remember` 内置工具，使模型可以把显式选择的记忆写入正确目标桶并获得结构化写入结果。
 */
@Component
public class MemoryRememberTool implements Tool {

    /**
     * 负责执行 memory 写入、校验和索引刷新闭环，避免工具类自己协调多个底层组件。
     */
    private final LocalMemoryService localMemoryService;

    /**
     * 通过显式注入 memory service 固定工具职责，使其专注于工具协议与参数读取。
     */
    public MemoryRememberTool(LocalMemoryService localMemoryService) {
        this.localMemoryService = localMemoryService;
    }

    /**
     * 暴露 `memory.remember` 的最小输入 schema，要求模型显式提供目标桶、内容和触发原因。
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "memory.remember",
                "把一条记忆写入 USER.md、MEMORY.md 或 memory/YYYY-MM-DD.md，并返回结构化写入元数据。",
                ToolInputSchema.object(
                        Map.of(
                                "target", new ToolInputProperty("string", "目标桶，只允许 user_profile、long_term 或 session_log。"),
                                "category", new ToolInputProperty("string", "当 target=user_profile 时必填，只允许 preferred_name、preference、habit、taboo、constraint。"),
                                "content", new ToolInputProperty("string", "需要写入记忆系统的正文内容。"),
                                "reason", new ToolInputProperty("string", "触发本次记忆写入的原因，例如 user_confirmed。"),
                                "confidence", new ToolInputProperty("number", "可选置信度，用于记录该记忆的稳定性判断。")
                        ),
                        List.of("target", "content", "reason")
                )
        );
    }

    /**
     * 声明 `memory.remember` 会改写本地记忆事实源，但当前仍允许在受控应用服务校验后直接执行。
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
     * 读取标准化参数并委托 memory service 执行写入，最后把结果转换成结构化工具载荷。
     */
    @Override
    public Map<String, Object> execute(ToolCallRequest request) {
        if (request.executionContext() == null) {
            throw new ToolArgumentException("memory.remember 需要运行时上下文。", Map.of("field", "executionContext"));
        }
        Map<String, Object> arguments = request.arguments();
        MemoryWriteResult writeResult = localMemoryService.remember(
                asString(arguments.get("target")),
                asNullableString(arguments.get("category")),
                asString(arguments.get("content")),
                asString(arguments.get("reason")),
                asNullableDouble(arguments.get("confidence")),
                request.executionContext()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetBucket", writeResult.targetBucket());
        payload.put("relativePath", writeResult.relativePath());
        payload.put("duplicateSuppressed", writeResult.duplicateSuppressed());
        if (writeResult.persistedCategory() != null) {
            payload.put("persistedCategory", writeResult.persistedCategory());
        }
        return Map.copyOf(payload);
    }

    /**
     * 把必填字符串参数统一转换为文本；缺失值保留给下游 service 做统一错误收敛。
     */
    private String asString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    /**
     * 把可选字符串参数转换为文本，方便区分“未传值”和“传入非法类型”两种场景。
     */
    private String asNullableString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    /**
     * 把可选数字参数转换为 Double，避免工具直接依赖模型输出的具体数字实现类型。
     */
    private Double asNullableDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
