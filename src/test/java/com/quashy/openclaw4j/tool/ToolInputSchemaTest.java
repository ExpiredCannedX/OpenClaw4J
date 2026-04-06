package com.quashy.openclaw4j.tool;

import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证统一工具 schema 既能保留 MCP 的通用 JSON Schema 结构，也不会破坏本地工具的简洁构造方式。
 */
class ToolInputSchemaTest {

    /**
     * 通用 schema 工厂必须无损保留嵌套对象、数组和 enum 约束，避免 MCP tool 参数语义在映射阶段丢失。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldPreserveNestedJsonSchemaDocumentWhenCreatedFromGeneralSchema() throws Exception {
        Map<String, Object> nestedItemSchema = new LinkedHashMap<>();
        nestedItemSchema.put("type", "string");
        nestedItemSchema.put("enum", List.of("summary", "full"));

        Map<String, Object> filtersSchema = new LinkedHashMap<>();
        filtersSchema.put("type", "object");
        filtersSchema.put("properties", Map.of(
                "tags", Map.of(
                        "type", "array",
                        "items", nestedItemSchema
                )
        ));
        filtersSchema.put("required", List.of("tags"));

        Map<String, Object> schemaDocument = new LinkedHashMap<>();
        schemaDocument.put("type", "object");
        schemaDocument.put("properties", Map.of("filters", filtersSchema));
        schemaDocument.put("required", List.of("filters"));

        Method fromJsonSchema = ToolInputSchema.class.getMethod("fromJsonSchema", Map.class);
        ToolInputSchema schema = (ToolInputSchema) fromJsonSchema.invoke(null, schemaDocument);
        Method rawSchemaAccessor = ToolInputSchema.class.getMethod("schema");
        Map<String, Object> rawSchema = (Map<String, Object>) rawSchemaAccessor.invoke(schema);

        assertThat(rawSchema).containsEntry("type", "object");
        Map<String, Object> properties = (Map<String, Object>) rawSchema.get("properties");
        Map<String, Object> filters = (Map<String, Object>) properties.get("filters");
        assertThat(filters).containsEntry("type", "object");
        Map<String, Object> nestedProperties = (Map<String, Object>) filters.get("properties");
        Map<String, Object> tags = (Map<String, Object>) nestedProperties.get("tags");
        assertThat(tags).containsEntry("type", "array");
        assertThat((Map<String, Object>) tags.get("items"))
                .containsEntry("type", "string")
                .containsEntry("enum", List.of("summary", "full"));
    }

    /**
     * 本地 helper 仍然必须产出合法的 object schema，避免现有内置工具定义方式在升级后整体失效。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepLocalHelperConvenienceForSimpleObjectSchema() throws Exception {
        ToolInputSchema schema = ToolInputSchema.object(
                Map.of("text", new ToolInputProperty("string", "需要回显的文本。")),
                List.of("text")
        );

        Method rawSchemaAccessor = ToolInputSchema.class.getMethod("schema");
        Map<String, Object> rawSchema = (Map<String, Object>) rawSchemaAccessor.invoke(schema);

        assertThat(rawSchema).containsEntry("type", "object");
        assertThat(rawSchema).containsEntry("required", List.of("text"));
        assertThat((Map<String, Object>) rawSchema.get("properties"))
                .containsEntry("text", Map.of(
                        "type", "string",
                        "description", "需要回显的文本。"
                ));
    }
}
