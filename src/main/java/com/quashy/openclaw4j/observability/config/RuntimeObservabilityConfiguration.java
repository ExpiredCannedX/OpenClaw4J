package com.quashy.openclaw4j.observability.config;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import com.quashy.openclaw4j.observability.runtime.DefaultRuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.sink.CompositeRuntimeObservationSink;
import com.quashy.openclaw4j.observability.sink.ConsoleRuntimeObservationSink;
import com.quashy.openclaw4j.observability.sink.NoopRuntimeObservationSink;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一装配运行期可观测性的默认发布器和 sink，确保应用配置能集中控制模式与输出端口。
 */
@Configuration
public class RuntimeObservabilityConfiguration {

    /**
     * 根据应用配置组装最终启用的 sink 列表，并在没有真实 sink 时回退到 noop 实现。
     */
    @Bean
    public RuntimeObservationSink runtimeObservationSink(OpenClawProperties properties) {
        List<RuntimeObservationSink> sinks = new ArrayList<>();
        if (properties.observability().consoleEnabled()) {
            sinks.add(new ConsoleRuntimeObservationSink());
        }
        if (sinks.isEmpty()) {
            return new NoopRuntimeObservationSink();
        }
        if (sinks.size() == 1) {
            return sinks.getFirst();
        }
        return new CompositeRuntimeObservationSink(sinks);
    }

    /**
     * 组装带统一时间源的默认发布器，让业务主链路只依赖抽象发布接口而不直接感知模式裁剪逻辑。
     */
    @Bean
    public RuntimeObservationPublisher runtimeObservationPublisher(
            OpenClawProperties properties,
            RuntimeObservationSink runtimeObservationSink
    ) {
        return new DefaultRuntimeObservationPublisher(
                properties.observability().mode(),
                properties.observability().verbosePreviewLength(),
                runtimeObservationSink,
                Clock.systemUTC()
        );
    }
}
