package com.quashy.openclaw4j.observability.config;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.port.RuntimeObservationSink;
import com.quashy.openclaw4j.observability.runtime.DefaultRuntimeObservationPublisher;
import com.quashy.openclaw4j.observability.sink.ConsoleRuntimeObservationSink;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 observability 包拆分后，配置装配仍能稳定产出默认 sink 与发布器。
 */
class RuntimeObservabilityConfigurationTest {

    /**
     * 当控制台 sink 开启时，配置类必须返回控制台 sink，并让默认发布器继续依赖拆分后的抽象类型。
     */
    @Test
    void shouldAssembleConsoleSinkAndDefaultPublisherAfterPackageSplit() {
        RuntimeObservabilityConfiguration configuration = new RuntimeObservabilityConfiguration();
        OpenClawProperties properties = new OpenClawProperties(
                "workspace",
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", ""),
                new OpenClawProperties.McpProperties(Duration.ofSeconds(20), java.util.Map.of()),
                new OpenClawProperties.ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160),
                new OpenClawProperties.ReminderProperties(".openclaw/reminders.sqlite"),
                new OpenClawProperties.SchedulerProperties(Duration.ofSeconds(15), 20, 3, Duration.ofMinutes(3)),
                new OpenClawProperties.MemoryProperties(".openclaw/memory-index.sqlite"),
                new OpenClawProperties.ToolSafetyProperties(null, null, null, null)
        );

        RuntimeObservationSink sink = configuration.runtimeObservationSink(properties);
        RuntimeObservationPublisher publisher = configuration.runtimeObservationPublisher(properties, sink);

        assertThat(sink).isInstanceOf(ConsoleRuntimeObservationSink.class);
        assertThat(publisher).isInstanceOf(DefaultRuntimeObservationPublisher.class);
    }
}
