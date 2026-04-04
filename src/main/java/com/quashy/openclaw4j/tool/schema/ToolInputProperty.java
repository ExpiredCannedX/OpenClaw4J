package com.quashy.openclaw4j.tool.schema;

import org.springframework.util.Assert;

/**
 * 表示单个工具参数的最小 schema 信息，使模型能理解参数类型与业务语义。
 */
public record ToolInputProperty(
        /**
         * 说明参数的基础类型，当前主要用于让模型知道应构造字符串、数字或对象。
         */
        String type,
        /**
         * 解释参数的业务含义和关键约束，减少模型误填参数的概率。
         */
        String description
) {

    /**
     * 在属性创建时校验类型与描述均存在，避免把无意义的属性暴露给模型。
     */
    public ToolInputProperty {
        Assert.hasText(type, "property type must not be blank");
        Assert.hasText(description, "property description must not be blank");
    }
}
