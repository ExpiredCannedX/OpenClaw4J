package com.quashy.openclaw4j.tool;

/**
 * 表示工具执行后的统一结果边界，使上游只需要区分成功载荷和结构化错误。
 */
public sealed interface ToolExecutionResult permits ToolExecutionSuccess, ToolExecutionError {

    /**
     * 返回产出该结果的工具名，便于 prompt 组装时定位观察结果来源。
     */
    String toolName();
}
