package com.quashy.openclaw4j.tool.api;

import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionResult;

/**
 * 抽象同步工具执行边界，使 Agent Core 只依赖统一结果而不处理异常分类细节。
 */
public interface ToolExecutor {

    /**
     * 执行一次标准化工具调用，并把所有结果归一化为成功或错误结构。
     */
    ToolExecutionResult execute(ToolCallRequest request);
}
