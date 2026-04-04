package com.quashy.openclaw4j.observability.runtime;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证运行期可观测性发布器会按模式过滤事件、裁剪详细负载，并把最终结果统一路由到 sink。
 */
class DefaultRuntimeObservationPublisherTest {

    /**
     * `TIMELINE` 模式只能暴露摘要字段，避免详细调试负载在默认模式下污染控制台输出。
     */
    @Test
    void shouldOnlyEmitSummaryPayloadInTimelineMode() {
        RecordingRuntimeObservationSink sink = new RecordingRuntimeObservationSink();
        DefaultRuntimeObservationPublisher publisher = new DefaultRuntimeObservationPublisher(
                RuntimeObservationMode.TIMELINE,
                12,
                sink,
                Clock.fixed(Instant.parse("2026-04-04T08:00:00Z"), ZoneOffset.UTC)
        );
        TraceContext traceContext = publisher.createTrace("telegram", "2001", "1001");

        publisher.emit(
                traceContext,
                "agent.model.decision.completed",
                RuntimeObservationPhase.MODEL,
                RuntimeObservationLevel.INFO,
                "DefaultAgentFacade",
                Map.of("decisionType", "tool_call"),
                Map.of("rawDecisionPreview", "```json {\"type\":\"tool_call\"}```")
        );

        assertThat(sink.events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.payload()).containsEntry("decisionType", "tool_call");
                    assertThat(event.payload()).doesNotContainKey("rawDecisionPreview");
                    assertThat(event.traceContext().runId()).isEqualTo(traceContext.runId());
                });
    }

    /**
     * `VERBOSE` 模式允许输出截断后的预览字段，便于深度排障但不直接暴露完整原文。
     */
    @Test
    void shouldIncludeTruncatedVerbosePreviewInVerboseMode() {
        RecordingRuntimeObservationSink sink = new RecordingRuntimeObservationSink();
        DefaultRuntimeObservationPublisher publisher = new DefaultRuntimeObservationPublisher(
                RuntimeObservationMode.VERBOSE,
                10,
                sink,
                Clock.fixed(Instant.parse("2026-04-04T08:00:00Z"), ZoneOffset.UTC)
        );
        TraceContext traceContext = publisher.createTrace("telegram", "2001", "1001");

        publisher.emit(
                traceContext,
                "agent.model.final_reply.completed",
                RuntimeObservationPhase.REPLY,
                RuntimeObservationLevel.INFO,
                "DefaultAgentFacade",
                Map.of("status", "success"),
                Map.of("replyPreview", "这是一段明显超过十个字符的最终回复预览")
        );

        assertThat(sink.events)
                .singleElement()
                .satisfies(event -> assertThat(event.payload())
                        .containsEntry("status", "success")
                        .containsEntry("replyPreview", "这是一段明显超过十个..."));
    }

    /**
     * `ERRORS` 模式必须过滤普通时间线事件，仅保留异常和关键跳过等高优先级观测结果。
     */
    @Test
    void shouldFilterInfoEventsWhenModeIsErrors() {
        RecordingRuntimeObservationSink sink = new RecordingRuntimeObservationSink();
        DefaultRuntimeObservationPublisher publisher = new DefaultRuntimeObservationPublisher(
                RuntimeObservationMode.ERRORS,
                20,
                sink,
                Clock.fixed(Instant.parse("2026-04-04T08:00:00Z"), ZoneOffset.UTC)
        );
        TraceContext traceContext = publisher.createTrace("telegram", "2001", "1001");

        publisher.emit(
                traceContext,
                "telegram.update.received",
                RuntimeObservationPhase.INGRESS,
                RuntimeObservationLevel.INFO,
                "TelegramWebhookService",
                Map.of("channel", "telegram")
        );
        publisher.emit(
                traceContext,
                "telegram.outbound.failed",
                RuntimeObservationPhase.OUTBOUND,
                RuntimeObservationLevel.ERROR,
                "TelegramWebhookService",
                Map.of("status", "failed")
        );

        assertThat(sink.events)
                .singleElement()
                .satisfies(event -> assertThat(event.eventType()).isEqualTo("telegram.outbound.failed"));
    }

    /**
     * 记录 sink 接收到的最终事件，便于直接断言发布器输出而不依赖控制台文本。
     */
    private static final class RecordingRuntimeObservationSink implements RuntimeObservationSink {

        /**
         * 保存发布器最终路由到 sink 的事件，用于测试模式过滤和负载裁剪后的结果。
         */
        private final List<RuntimeObservationEvent> events = new ArrayList<>();

        /**
         * 收集每一次真正下沉到 sink 的事件，模拟生产环境中的多 sink 消费场景。
         */
        @Override
        public void emit(RuntimeObservationEvent event) {
            events.add(event);
        }
    }
}
