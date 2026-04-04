package com.quashy.openclaw4j.tool.model;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.model.TraceContext;

import java.nio.file.Path;

/**
 * 承载工具执行所需的运行时上下文，使工具能够读取身份、消息来源、trace 与 workspace 根路径等系统事实。
 */
public record ToolExecutionContext(
        /**
         * 标识当前请求对应的内部用户，供需要 provenance 的工具写入稳定身份语义。
         */
        InternalUserId userId,
        /**
         * 标识当前请求所属的内部会话，供 session 级工具读取和过滤当前会话上下文。
         */
        InternalConversationId conversationId,
        /**
         * 保存触发本次工具调用的标准化消息来源，避免工具直接依赖渠道协议对象。
         */
        NormalizedDirectMessage sourceMessage,
        /**
         * 保存当前请求共享的 trace 上下文，使工具发出的运行事件仍能串到同一 `runId`。
         */
        TraceContext traceContext,
        /**
         * 指向当前请求使用的 workspace 根目录，供本地文件型工具解析事实源与索引路径。
         */
        Path workspaceRoot
) {

    /**
     * 在上下文创建时规范化 workspace 根路径，避免不同调用方传入相对路径导致工具侧判定不一致。
     */
    public ToolExecutionContext {
        workspaceRoot = workspaceRoot != null ? workspaceRoot.toAbsolutePath().normalize() : null;
    }
}
