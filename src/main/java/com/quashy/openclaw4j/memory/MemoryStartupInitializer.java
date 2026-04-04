package com.quashy.openclaw4j.memory;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 在应用启动后初始化 memory 索引，使 `memory.search` 在首个请求到来前就具备最小可用性。
 */
@Component
public class MemoryStartupInitializer implements ApplicationRunner {

    /**
     * 负责执行启动期索引刷新闭环，避免把扫描逻辑直接塞入 Spring 生命周期钩子中。
     */
    private final LocalMemoryService localMemoryService;

    /**
     * 提供 workspace 根路径等启动期上下文，供索引初始化时解析本地文件位置。
     */
    private final OpenClawProperties properties;

    /**
     * 负责创建启动期 trace 上下文，使 memory 初始化事件也能沿用统一观测模型。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 通过显式依赖注入固定启动期初始化边界，让主业务链路无需感知 memory 索引预热。
     */
    public MemoryStartupInitializer(
            LocalMemoryService localMemoryService,
            OpenClawProperties properties,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.localMemoryService = localMemoryService;
        this.properties = properties;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 在 Spring Boot 应用启动完成后执行一次 memory 索引刷新，并跳过一切非必要的请求级身份信息。
     */
    @Override
    public void run(ApplicationArguments args) {
        localMemoryService.refreshIndexOnStartup(new ToolExecutionContext(
                null,
                null,
                new NormalizedDirectMessage("system", null, null, null, "startup-index-refresh"),
                runtimeObservationPublisher.createTrace("system", properties.workspaceRoot(), "startup-memory-index"),
                Path.of(properties.workspaceRoot())
        ));
    }
}
