package com.quashy.openclaw4j.tool.builtin.time;

import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 提供当前服务端时间的最小内置工具，用于验证 Tool System foundation 的目录暴露、执行与观察回填闭环。
 */
@Component
public class TimeTool implements Tool {

    /**
     * 决定时间读取来源，使生产环境可使用系统时钟，而测试可以注入固定时间。
     */
    private final Clock clock;

    /**
     * 为 Spring 组件装配提供默认系统时钟，避免为了首个内置工具再引入额外配置。
     */
    public TimeTool() {
        this(Clock.systemDefaultZone());
    }

    /**
     * 允许测试或调用方显式指定时钟来源，确保时间相关断言稳定可重复。
     */
    private TimeTool(Clock clock) {
        this.clock = clock;
    }

    /**
     * 提供固定时钟工厂方法，便于跨包测试构造稳定的 `time` 工具实例而不影响 Spring 默认装配。
     */
    public static TimeTool forClock(Clock clock) {
        return new TimeTool(clock);
    }

    /**
     * 暴露零必填参数 schema，让模型清楚该工具无需额外参数即可调用。
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "time",
                "返回当前服务端时间戳、时区标识和人类可读时间文本。",
                ToolInputSchema.object(Map.of(), List.of())
        );
    }

    /**
     * 返回当前服务端时间的机器可读与人类可读视图，作为首个工具的最小成功载荷。
     */
    @Override
    public Map<String, Object> execute(ToolCallRequest request) {
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        return Map.of(
                "currentTimestamp", currentTime.toString(),
                "timezoneId", clock.getZone().getId(),
                "humanReadable", currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }
}
