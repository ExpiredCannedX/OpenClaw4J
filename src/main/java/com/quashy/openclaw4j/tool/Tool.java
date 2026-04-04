package com.quashy.openclaw4j.tool;

import java.util.Map;

/**
 * 定义本地同步工具的最小执行边界，使 Agent Core 可以通过统一抽象发现并调用内置工具。
 */
public interface Tool {

    /**
     * 返回暴露给模型和注册中心的标准化工具定义，确保目录视图不依赖具体实现细节。
     */
    ToolDefinition definition();

    /**
     * 按统一请求模型同步执行工具，并只返回归一化后的业务载荷，异常由执行器统一收敛。
     */
    Map<String, Object> execute(ToolCallRequest request);
}
