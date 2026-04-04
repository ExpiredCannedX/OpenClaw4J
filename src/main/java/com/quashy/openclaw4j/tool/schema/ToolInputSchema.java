package com.quashy.openclaw4j.tool.schema;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表示工具入参的最小 schema 视图，当前只覆盖 foundation 阶段所需的对象参数约束。
 */
public record ToolInputSchema(
        /**
         * 标记当前 schema 的顶层类型，foundation 阶段固定为 `object`。
         */
        String type,
        /**
         * 按参数名暴露每个属性的类型和语义说明，供模型构造调用参数时参考。
         */
        Map<String, ToolInputProperty> properties,
        /**
         * 标记必须提供的参数名称列表，用于表达最小参数约束。
         */
        List<String> required
) {

    /**
     * 在 schema 创建时冻结属性集合并校验必填项合法性，避免目录暴露出自相矛盾的约束。
     */
    public ToolInputSchema {
        Assert.hasText(type, "schema type must not be blank");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
        required = List.copyOf(Objects.requireNonNull(required, "required must not be null"));
        Assert.isTrue(properties.keySet().containsAll(required), "required fields must exist in properties");
    }

    /**
     * 创建 foundation 阶段使用的对象 schema，收敛重复的 `object` 构造细节。
     */
    public static ToolInputSchema object(Map<String, ToolInputProperty> properties, List<String> required) {
        return new ToolInputSchema("object", properties, required);
    }
}
