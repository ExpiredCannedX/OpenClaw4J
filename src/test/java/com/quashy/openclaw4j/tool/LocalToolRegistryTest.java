package com.quashy.openclaw4j.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证本地 ToolRegistry 会以唯一名称维护工具目录，并向上层暴露稳定的工具定义视图。
 */
class LocalToolRegistryTest {

    /**
     * 注册成功的工具必须能够按唯一名称解析，并把标准化定义暴露给 Agent Core。
     */
    @Test
    void shouldListDefinitionsAndResolveToolByExactName() {
        ToolRegistry toolRegistry = new LocalToolRegistry(List.of(createEchoTool()));

        assertThat(toolRegistry.listDefinitions())
                .singleElement()
                .satisfies(definition -> {
                    assertThat(definition.name()).isEqualTo("echo");
                    assertThat(definition.description()).contains("回显");
                    assertThat(definition.inputSchema().required()).containsExactly("text");
                });
        assertThat(toolRegistry.findByName("echo"))
                .hasValueSatisfying(tool -> assertThat(tool.definition().name()).isEqualTo("echo"));
    }

    /**
     * 出现重复工具名时必须在注册阶段直接拒绝，避免把歧义目录暴露给模型和执行器。
     */
    @Test
    void shouldRejectDuplicateToolNames() {
        Tool first = createEchoTool();
        Tool second = createEchoTool();

        assertThatThrownBy(() -> new LocalToolRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("echo");
    }

    /**
     * 构造一个最小 echo 工具定义，用于验证目录暴露与名称解析行为。
     */
    private Tool createEchoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "echo",
                        "把 text 参数原样回显的测试工具。",
                        ToolInputSchema.object(
                                Map.of("text", new ToolInputProperty("string", "需要回显的文本。")),
                                List.of("text")
                        )
                );
            }

            @Override
            public Map<String, Object> execute(ToolCallRequest request) {
                return request.arguments();
            }
        };
    }
}
