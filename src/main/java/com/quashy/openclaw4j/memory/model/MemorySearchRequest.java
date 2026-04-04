package com.quashy.openclaw4j.memory.model;

import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.springframework.util.Assert;

/**
 * 描述一次 memory.search 查询请求，使索引层可以稳定读取查询词、范围和当前会话上下文。
 */
public record MemorySearchRequest(
        /**
         * 承载需要在本地索引中做全文匹配的关键词或短语。
         */
        String query,
        /**
         * 指定本次检索的范围，决定是否限制到当前会话或某个固定目标桶。
         */
        MemorySearchScope scope,
        /**
         * 承载当前工具运行的上下文，用于 session scope 过滤和观测事件关联。
         */
        ToolExecutionContext executionContext
) {

    /**
     * 在查询创建时收敛必填字段，避免索引层继续处理空查询或缺失上下文的无效请求。
     */
    public MemorySearchRequest {
        Assert.hasText(query, "query must not be blank");
        Assert.notNull(scope, "scope must not be null");
        Assert.notNull(executionContext, "executionContext must not be null");
    }
}
