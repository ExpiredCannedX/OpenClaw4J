package com.quashy.openclaw4j.observability.sink;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
/**
 * 在未启用任何真实 sink 时吞掉所有事件，保证主链路仍保持统一调用方式而不产生额外输出。
 */
public class NoopRuntimeObservationSink implements RuntimeObservationSink {

    /**
     * 显式忽略事件，避免业务层为了关闭输出而分叉主链路逻辑。
     */
    @Override
    public void emit(RuntimeObservationEvent event) {
        // 当前 sink 的职责就是不做任何事。
    }
}
