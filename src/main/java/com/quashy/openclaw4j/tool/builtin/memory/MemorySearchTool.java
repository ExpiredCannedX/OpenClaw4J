package com.quashy.openclaw4j.tool.builtin.memory;

import com.quashy.openclaw4j.memory.LocalMemoryService;
import com.quashy.openclaw4j.memory.model.MemorySearchMatch;
import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.model.ToolArgumentException;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.schema.ToolInputProperty;
import com.quashy.openclaw4j.tool.schema.ToolInputSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供 `memory.search` 内置工具，使模型能够在本地 SQLite FTS 索引上执行最小范围控制的全文检索。
 */
@Component
public class MemorySearchTool implements Tool {

    /**
     * 负责执行 memory 检索与 scope 过滤，避免工具类自己处理文件与索引细节。
     */
    private final LocalMemoryService localMemoryService;

    /**
     * 通过显式注入 memory service 固定工具职责，使其专注于工具输入输出协议。
     */
    public MemorySearchTool(LocalMemoryService localMemoryService) {
        this.localMemoryService = localMemoryService;
    }

    /**
     * 暴露 `memory.search` 的最小输入 schema，让模型能显式指定查询词和检索范围。
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "memory.search",
                "在本地 SQLite FTS 记忆索引中按关键词检索，并返回结构化匹配列表。",
                ToolInputSchema.object(
                        Map.of(
                                "query", new ToolInputProperty("string", "需要检索的关键词或短语。"),
                                "scope", new ToolInputProperty("string", "可选范围，只允许 all、user_profile、long_term、session。")
                        ),
                        List.of("query")
                )
        );
    }

    /**
     * 读取标准化参数并委托 memory service 搜索，再把结构化命中结果压平为工具载荷。
     */
    @Override
    public Map<String, Object> execute(ToolCallRequest request) {
        if (request.executionContext() == null) {
            throw new ToolArgumentException("memory.search 需要运行时上下文。", Map.of("field", "executionContext"));
        }
        String query = request.arguments().get("query") instanceof String stringValue ? stringValue : null;
        String scope = request.arguments().get("scope") instanceof String stringValue ? stringValue : "all";
        List<MemorySearchMatch> matches = localMemoryService.search(query, scope, request.executionContext());
        return Map.of(
                "query", query,
                "scope", scope,
                "matches", matches.stream().map(this::toPayload).toList()
        );
    }

    /**
     * 把索引层命中结果转换成稳定的 Map 载荷，避免工具输出直接暴露内部 record 实现。
     */
    private Map<String, Object> toPayload(MemorySearchMatch match) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("relativePath", match.relativePath());
        payload.put("targetBucket", match.targetBucket());
        payload.put("lineStart", match.lineStart());
        payload.put("lineEnd", match.lineEnd());
        payload.put("previewSnippet", match.previewSnippet());
        payload.put("score", match.score());
        return Map.copyOf(payload);
    }
}
