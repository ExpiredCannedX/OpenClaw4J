package com.quashy.openclaw4j.observability.sink;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import java.util.List;

/**
 * 把同一条运行事件广播到多个 sink，为未来同时接入控制台、文件或 Web UI 预留最小组合能力。
 */
public class CompositeRuntimeObservationSink implements RuntimeObservationSink {

    /**
     * 保存当前启用的所有下游 sink，按声明顺序逐个消费同一条事件。
     */
    private final List<RuntimeObservationSink> sinks;

    /**
     * 通过组合多个 sink 保持事件生产者只有一条发布路径，避免不同观测面各自埋点。
     */
    public CompositeRuntimeObservationSink(List<RuntimeObservationSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    /**
     * 依次把事件路由到所有已启用 sink，保持每个观测面的输入完全一致。
     */
    @Override
    public void emit(RuntimeObservationEvent event) {
        sinks.forEach(sink -> sink.emit(event));
    }
}
