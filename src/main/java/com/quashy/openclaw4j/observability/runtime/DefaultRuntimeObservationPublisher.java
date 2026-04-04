package com.quashy.openclaw4j.observability.runtime;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一负责 trace 创建、模式过滤和详细字段裁剪，确保业务层只声明事件而不关心输出细节。
 */
public class DefaultRuntimeObservationPublisher implements RuntimeObservationPublisher {

    /**
     * 表示当前进程启用的观测模式，是所有事件过滤与裁剪的唯一依据。
     */
    private final RuntimeObservationMode mode;

    /**
     * 控制详细预览字段的最大字符数，避免 `VERBOSE` 模式直接泄漏完整上下文。
     */
    private final int verbosePreviewLength;

    /**
     * 承载最终事件输出的 sink 组合，业务层永远只通过发布器间接访问它。
     */
    private final RuntimeObservationSink sink;

    /**
     * 提供统一时间源，便于测试稳定断言时间戳并确保所有事件时间口径一致。
     */
    private final Clock clock;

    /**
     * 通过显式构造参数固定模式、裁剪规则和输出端口，避免业务代码自己处理这些横切关注点。
     */
    public DefaultRuntimeObservationPublisher(
            RuntimeObservationMode mode,
            int verbosePreviewLength,
            RuntimeObservationSink sink,
            Clock clock
    ) {
        this.mode = mode;
        this.verbosePreviewLength = verbosePreviewLength > 0 ? verbosePreviewLength : 160;
        this.sink = sink;
        this.clock = clock;
    }

    /**
     * 为一轮新的消息处理生成唯一 `runId`，让后续 Telegram、Agent 与工具事件能被同次聚合。
     */
    @Override
    public TraceContext createTrace(String channel, String externalConversationId, String externalMessageId) {
        return new TraceContext(
                UUID.randomUUID().toString(),
                channel,
                externalConversationId,
                externalMessageId,
                null,
                mode
        );
    }

    /**
     * 按当前模式过滤事件并合并允许暴露的详细字段，然后再交给 sink 做最终渲染。
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
        if (!shouldEmit(level)) {
            return;
        }
        Map<String, Object> effectivePayload = new LinkedHashMap<>(payload);
        if (mode == RuntimeObservationMode.VERBOSE) {
            sanitizeVerbosePayload(verbosePayload).forEach(effectivePayload::put);
        }
        sink.emit(new RuntimeObservationEvent(
                Instant.now(clock),
                eventType,
                phase,
                level,
                component,
                traceContext,
                Map.copyOf(effectivePayload),
                Map.copyOf(verbosePayload)
        ));
    }

    /**
     * 根据当前模式和事件级别决定是否真的输出，保证 `OFF` 和 `ERRORS` 都有稳定行为。
     */
    private boolean shouldEmit(RuntimeObservationLevel level) {
        if (mode == RuntimeObservationMode.OFF) {
            return false;
        }
        return mode != RuntimeObservationMode.ERRORS || level != RuntimeObservationLevel.INFO;
    }

    /**
     * 对详细负载做统一截断，只保留 `VERBOSE` 模式真正需要的预览信息。
     */
    private Map<String, Object> sanitizeVerbosePayload(Map<String, Object> verbosePayload) {
        Map<String, Object> sanitizedPayload = new LinkedHashMap<>();
        verbosePayload.forEach((key, value) -> sanitizedPayload.put(key, truncateValue(value)));
        return sanitizedPayload;
    }

    /**
     * 仅对字符串预览做长度裁剪，其余简单值原样保留，避免过度处理破坏调试语义。
     */
    private Object truncateValue(Object value) {
        if (!(value instanceof String stringValue) || stringValue.length() <= verbosePreviewLength) {
            return value;
        }
        return stringValue.substring(0, verbosePreviewLength) + "...";
    }
}
