package com.quashy.openclaw4j.observability.port;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;

/**
 * 抽象结构化运行事件的最终消费端，使控制台、文件或未来 Web UI 可以共享同一发布路径。
 */
public interface RuntimeObservationSink {

    /**
     * 消费一条已经完成模式裁剪的运行事件，具体 sink 自行决定如何渲染或持久化。
     */
    void emit(RuntimeObservationEvent event);
}
