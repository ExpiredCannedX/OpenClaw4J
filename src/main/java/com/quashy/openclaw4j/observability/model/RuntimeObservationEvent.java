package com.quashy.openclaw4j.observability.model;

import java.time.Instant;
import java.util.Map;

/**
 * 表示一条已经完成时间戳标记和模式裁剪的结构化运行事件，供 sink 直接消费与渲染。
 */
public record RuntimeObservationEvent(
        /**
         * 表示事件真正被发布到 sink 的时间点，用于恢复稳定的时间线顺序。
         */
        Instant timestamp,
        /**
         * 表示事件的稳定业务名称，便于跨 sink 和测试断言保持统一语义。
         */
        String eventType,
        /**
         * 标识事件所属的稳定主链路阶段，供开发者快速定位问题区间。
         */
        RuntimeObservationPhase phase,
        /**
         * 描述当前事件的严重等级，用于模式过滤和控制台高亮。
         */
        RuntimeObservationLevel level,
        /**
         * 标识发出当前事件的职责组件，便于在多模块主链路中快速定位边界。
         */
        String component,
        /**
         * 承载当前事件所属运行的关联上下文，保证同一轮处理链路能被可靠串联。
         */
        TraceContext traceContext,
        /**
         * 保存已经按当前模式裁剪完成的最终负载，是 sink 应展示的唯一结构化字段集合。
         */
        Map<String, Object> payload,
        /**
         * 保留原始详细负载，仅用于测试或二次加工；正式 sink 应优先消费 `payload`。
         */
        Map<String, Object> verbosePayload
) {
}
