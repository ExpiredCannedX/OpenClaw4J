package com.quashy.openclaw4j.tool.api;

import com.quashy.openclaw4j.tool.schema.ToolDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 抽象工具目录与按名解析能力，使 Agent Core 不需要了解工具装配来源。
 */
public interface ToolRegistry {

    /**
     * 返回当前请求可见的标准化工具定义列表，供 prompt 组装阶段暴露给模型。
     */
    List<ToolDefinition> listDefinitions();

    /**
     * 按唯一工具名解析具体工具实现，供执行器执行同步工具调用。
     */
    Optional<Tool> findByName(String toolName);
}
