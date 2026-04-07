package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.agent.prompt.AgentPromptAssembler;
import com.quashy.openclaw4j.conversation.NormalizedDirectMessage;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证规划阶段 prompt 会把统一工具目录中的完整 JSON Schema 约束直接暴露给模型，而不是退化为扁平字段摘要。
 */
class AgentPromptAssemblerTest {

    /**
     * 当工具目录中存在嵌套 schema 时，prompt 必须保留 object、array 和 enum 细节，避免模型看到失真的调用约束。
     */
    @Test
    void shouldRenderFullNestedJsonSchemaInPlanningPrompt() throws Exception {
        Method fromJsonSchema = ToolInputSchema.class.getMethod("fromJsonSchema", Map.class);
        ToolInputSchema schema = (ToolInputSchema) fromJsonSchema.invoke(null, createNestedSchemaDocument());
        ToolDefinition toolDefinition = new ToolDefinition(
                "mcp.filesystem.search_files",
                "根据过滤条件搜索文件。",
                schema
        );

        AgentPrompt prompt = new AgentPromptAssembler().assemblePlanningPrompt(
                new WorkspaceSnapshot(
                        List.of(new WorkspaceFileContent("SOUL.md", "规则")),
                        List.of(new WorkspaceFileContent("MEMORY.md", "记忆")),
                        List.of()
                ),
                Optional.empty(),
                List.of(),
                new NormalizedDirectMessage("dev", "user-1", "dm-1", "msg-1", "帮我找日志"),
                List.of(toolDefinition)
        );

        assertThat(prompt.content())
                .contains("name: mcp.filesystem.search_files")
                .contains("\"filters\"")
                .contains("\"tags\"")
                .contains("\"items\"")
                .contains("\"enum\"")
                .contains("arguments 必须是 JSON 对象")
                .contains("summary")
                .contains("full");
    }

    /**
     * 构造一个包含嵌套 object、array 和 enum 的输入 schema，用于验证 prompt 不会丢失 MCP 约束细节。
     */
    private Map<String, Object> createNestedSchemaDocument() {
        Map<String, Object> modeItemSchema = new LinkedHashMap<>();
        modeItemSchema.put("type", "string");
        modeItemSchema.put("enum", List.of("summary", "full"));

        Map<String, Object> tagsSchema = new LinkedHashMap<>();
        tagsSchema.put("type", "array");
        tagsSchema.put("items", modeItemSchema);

        Map<String, Object> filtersSchema = new LinkedHashMap<>();
        filtersSchema.put("type", "object");
        filtersSchema.put("properties", Map.of("tags", tagsSchema));
        filtersSchema.put("required", List.of("tags"));

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "object");
        rootSchema.put("properties", Map.of("filters", filtersSchema));
        rootSchema.put("required", List.of("filters"));
        return rootSchema;
    }
}
