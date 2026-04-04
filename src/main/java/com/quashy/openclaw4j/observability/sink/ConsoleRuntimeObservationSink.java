package com.quashy.openclaw4j.observability.sink;

import com.quashy.openclaw4j.observability.model.RuntimeObservationEvent;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把结构化运行事件渲染为开发者可直接阅读的控制台时间线，优先解决联调时“没有任何信号”的问题。
 */
public class ConsoleRuntimeObservationSink implements RuntimeObservationSink {

    /**
     * 复用独立 logger 输出运行期时间线，避免与业务日志混淆且便于后续单独过滤。
     */
    private static final Logger log = LoggerFactory.getLogger(ConsoleRuntimeObservationSink.class);

    /**
     * 以单行时间线形式输出事件，让开发者能在控制台里快速查看阶段、组件和关键负载。
     */
    @Override
    public void emit(RuntimeObservationEvent event) {
        log.info(
                "[run:{}][{}][{}][{}] {}",
                event.traceContext().runId(),
                event.level(),
                event.phase(),
                event.component(),
                formatPayload(event.eventType(), event.payload())
        );
    }

    /**
     * 把稳定事件名与结构化负载拼成紧凑字符串，保证控制台既可读又不依赖 JSON 序列化格式。
     */
    private String formatPayload(String eventType, Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return eventType;
        }
        return eventType + " " + payload.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
    }
}
