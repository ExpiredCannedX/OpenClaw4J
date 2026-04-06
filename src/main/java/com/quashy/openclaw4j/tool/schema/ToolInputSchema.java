package com.quashy.openclaw4j.tool.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 表示暴露给模型消费的统一 JSON Schema 文档，既能容纳 MCP 的复杂约束，也保留本地工具的简洁构造方式。
 */
public record ToolInputSchema(
        /**
         * 承载完整 JSON Schema 文档正文，供 prompt 渲染和 MCP schema 映射直接复用。
         */
        Map<String, Object> schema
) {

    /**
     * 统一使用稳定 ObjectMapper 渲染 schema 文本，避免 prompt 中出现不一致的 JSON 格式。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 在 schema 创建时递归冻结文档，避免目录暴露后被调用方继续篡改。
     */
    public ToolInputSchema {
        schema = immutableMapCopy(Objects.requireNonNull(schema, "schema must not be null"));
    }

    /**
     * 直接从通用 JSON Schema 文档创建统一 schema 视图，供 MCP tool 映射无损接入。
     */
    public static ToolInputSchema fromJsonSchema(Map<String, Object> schema) {
        return new ToolInputSchema(schema);
    }

    /**
     * 创建 foundation 阶段和本地工具仍可复用的对象 schema helper，收敛重复的 `object` 构造细节。
     */
    public static ToolInputSchema object(Map<String, ToolInputProperty> properties, List<String> required) {
        Assert.notNull(properties, "properties must not be null");
        Assert.notNull(required, "required must not be null");
        Assert.isTrue(properties.keySet().containsAll(required), "required fields must exist in properties");
        return new ToolInputSchema(Map.of(
                "type", "object",
                "properties", properties.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().toSchemaFragment()
                )),
                "required", List.copyOf(required)
        ));
    }

    /**
     * 返回顶层类型，便于现有本地工具测试继续以最小断言方式验证 schema 语义。
     */
    public String type() {
        Object value = schema.get("type");
        return value instanceof String stringValue ? stringValue : null;
    }

    /**
     * 返回顶层属性定义，兼容现有对本地 object schema 的轻量断言与读取习惯。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> properties() {
        Object value = schema.get("properties");
        if (value instanceof Map<?, ?> propertiesMap) {
            return (Map<String, Object>) propertiesMap;
        }
        return Map.of();
    }

    /**
     * 返回顶层必填字段列表，兼容现有对 object schema 的轻量断言与读取习惯。
     */
    @SuppressWarnings("unchecked")
    public List<String> required() {
        Object value = schema.get("required");
        if (value instanceof List<?> requiredFields) {
            return (List<String>) requiredFields;
        }
        return List.of();
    }

    /**
     * 把统一 schema 渲染成稳定的格式化 JSON，供 Agent Core 直接暴露给模型消费。
     */
    public String toPrettyJson() {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to render tool input schema.", exception);
        }
    }

    /**
     * 递归冻结 JSON 风格的 map 值，保证 schema 文档一旦创建就不会被后续调用方原地修改。
     */
    private static Map<String, Object> immutableMapCopy(Map<String, Object> source) {
        return source.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> immutableValue(entry.getValue())
        ));
    }

    /**
     * 递归冻结 JSON 风格值中的 map/list 结构，其余标量值按原样保留。
     */
    @SuppressWarnings("unchecked")
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> nestedMap) {
            return immutableMapCopy((Map<String, Object>) nestedMap);
        }
        if (value instanceof List<?> nestedList) {
            return nestedList.stream().map(ToolInputSchema::immutableValue).toList();
        }
        return value;
    }
}
