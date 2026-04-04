package com.quashy.openclaw4j.observability.port;

import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import java.util.Map;

/**
 * 抽象运行事件的创建与发布入口，避免业务主链路直接依赖具体 sink 和模式裁剪实现。
 */
public interface RuntimeObservationPublisher {

    /**
     * 为一次新的统一消息处理流程创建 trace 上下文，使后续事件都能共享同一 `runId`。
     */
    TraceContext createTrace(String channel, String externalConversationId, String externalMessageId);

    /**
     * 发布只包含摘要负载的事件，适用于大多数默认时间线场景。
     */
    default void emit(
            TraceContext traceContext,
            String eventType,
            RuntimeObservationPhase phase,
            RuntimeObservationLevel level,
            String component,
            Map<String, Object> payload
    ) {
        emit(traceContext, eventType, phase, level, component, payload, Map.of());
    }

    /**
     * 发布同时包含摘要与详细预览负载的事件，由实现决定是否按当前模式保留详细字段。
     */
    void emit(
            TraceContext traceContext,
            String eventType,
            RuntimeObservationPhase phase,
            RuntimeObservationLevel level,
            String component,
            Map<String, Object> payload,
            Map<String, Object> verbosePayload
    );
}
