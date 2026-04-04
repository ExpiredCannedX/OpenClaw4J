package com.quashy.openclaw4j.tool;

import com.quashy.openclaw4j.tool.builtin.time.TimeTool;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证内置 `time` 工具的 schema 与返回值都满足 foundation 阶段的最小约束。
 */
class TimeToolTest {

    /**
     * `time` 工具必须声明零必填参数 schema，并返回当前时间戳与时区信息。
     */
    @Test
    void shouldExposeEmptyInputSchemaAndReturnCurrentTimestampWithTimezone() {
        TimeTool timeTool = TimeTool.forClock(fixedClock());
        ToolDefinition definition = timeTool.definition();
        Map<String, Object> payload = timeTool.execute(new ToolCallRequest("time", Map.of()));

        assertThat(definition.name()).isEqualTo("time");
        assertThat(definition.inputSchema().required()).isEqualTo(List.of());
        assertThat(definition.inputSchema().properties()).isEmpty();
        assertThat(payload).containsEntry("currentTimestamp", "2026-04-03T16:09:10+08:00");
        assertThat(payload).containsEntry("timezoneId", "Asia/Shanghai");
        assertThat(payload.get("humanReadable")).asString().contains("2026-04-03 16:09:10");
    }

    /**
     * 固定时钟保证 `time` 工具测试在任意执行环境下都能得到稳定断言结果。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-03T08:09:10Z"), ZoneId.of("Asia/Shanghai"));
    }
}
