package com.quashy.openclaw4j.memory;

import com.quashy.openclaw4j.memory.index.SqliteMemoryIndexer;
import com.quashy.openclaw4j.memory.model.MemorySearchMatch;
import com.quashy.openclaw4j.memory.model.MemorySearchRequest;
import com.quashy.openclaw4j.memory.model.MemorySearchScope;
import com.quashy.openclaw4j.memory.model.MemoryTargetBucket;
import com.quashy.openclaw4j.memory.model.MemoryWriteRequest;
import com.quashy.openclaw4j.memory.model.MemoryWriteResult;
import com.quashy.openclaw4j.memory.model.UserProfileCategory;
import com.quashy.openclaw4j.memory.store.MarkdownMemoryStore;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import com.quashy.openclaw4j.tool.model.ToolArgumentException;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排 memory V1 的写入、检索和索引刷新闭环，使工具层只负责参数协议而不直接处理底层文件与 SQLite 细节。
 */
@Component
public class LocalMemoryService {

    /**
     * 负责把合法记忆请求持久化到 Markdown 事实源，保持文件驱动为唯一真相。
     */
    private final MarkdownMemoryStore memoryStore;

    /**
     * 负责把 Markdown 事实源刷新到 SQLite，并提供最小 FTS 检索能力。
     */
    private final SqliteMemoryIndexer memoryIndexer;

    /**
     * 负责发布 memory 写入、检索和启动期索引事件，保持 observability 横切层的一致模型。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * 通过显式注入 store、indexer 和发布器固定服务边界，避免工具直接协调多个底层组件。
     */
    public LocalMemoryService(
            MarkdownMemoryStore memoryStore,
            SqliteMemoryIndexer memoryIndexer,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.memoryStore = memoryStore;
        this.memoryIndexer = memoryIndexer;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 执行一次 memory.remember 请求，完成参数校验、Markdown 写入和受影响文件索引刷新。
     */
    public MemoryWriteResult remember(
            String rawTarget,
            String rawCategory,
            String content,
            String triggerReason,
            Double confidence,
            ToolExecutionContext executionContext
    ) {
        emitMemoryEvent(
                executionContext,
                "memory.remember.started",
                RuntimeObservationLevel.INFO,
                Map.of("targetBucket", rawTarget)
        );
        try {
            MemoryTargetBucket targetBucket = parseTarget(rawTarget);
            UserProfileCategory category = parseCategory(targetBucket, rawCategory);
            validateRememberInputs(content, triggerReason);
            MemoryWriteResult writeResult = memoryStore.write(new MemoryWriteRequest(
                    targetBucket,
                    category,
                    content,
                    triggerReason,
                    confidence,
                    executionContext
            ));
            memoryIndexer.refreshFile(writeResult.relativePath());
            emitMemoryEvent(
                    executionContext,
                    "memory.remember.completed",
                    RuntimeObservationLevel.INFO,
                    buildRememberPayload(writeResult)
            );
            return writeResult;
        } catch (ToolArgumentException exception) {
            emitFailureEvent(executionContext, "memory.remember.failed", exception);
            throw exception;
        } catch (Exception exception) {
            emitFailureEvent(executionContext, "memory.remember.failed", exception);
            throw new IllegalStateException("Failed to persist memory entry.", exception);
        }
    }

    /**
     * 执行一次 memory.search 请求，并按 scope 返回结构化匹配列表而不是原始文件正文。
     */
    public List<MemorySearchMatch> search(String query, String rawScope, ToolExecutionContext executionContext) {
        emitMemoryEvent(
                executionContext,
                "memory.search.started",
                RuntimeObservationLevel.INFO,
                Map.of("scope", rawScope == null || rawScope.isBlank() ? "all" : rawScope)
        );
        try {
            if (!StringUtils.hasText(query)) {
                throw new ToolArgumentException("缺少必填参数 query。", Map.of("field", "query"));
            }
            MemorySearchScope scope = parseScope(rawScope);
            List<MemorySearchMatch> matches = memoryIndexer.search(new MemorySearchRequest(query, scope, executionContext));
            emitMemoryEvent(
                    executionContext,
                    "memory.search.completed",
                    RuntimeObservationLevel.INFO,
                    Map.of(
                            "scope", scope.value(),
                            "matchCount", matches.size()
                    )
            );
            return matches;
        } catch (ToolArgumentException exception) {
            emitFailureEvent(executionContext, "memory.search.failed", exception);
            throw exception;
        } catch (Exception exception) {
            emitFailureEvent(executionContext, "memory.search.failed", exception);
            throw new IllegalStateException("Failed to search memory index.", exception);
        }
    }

    /**
     * 在应用启动后刷新所有已变更记忆文件的索引，使 `memory.search` 在首个请求前即可工作。
     */
    public void refreshIndexOnStartup(ToolExecutionContext executionContext) {
        emitMemoryEvent(
                executionContext,
                "memory.index.startup.started",
                RuntimeObservationLevel.INFO,
                Map.of("workspaceRoot", executionContext.workspaceRoot().toString())
        );
        try {
            memoryIndexer.refreshChangedFiles();
            emitMemoryEvent(
                executionContext,
                "memory.index.startup.completed",
                    RuntimeObservationLevel.INFO,
                    Map.of("workspaceRoot", executionContext.workspaceRoot().toString())
            );
        } catch (Exception exception) {
            emitFailureEvent(executionContext, "memory.index.startup.failed", exception);
            throw new IllegalStateException("Failed to refresh memory index on startup.", exception);
        }
    }

    /**
     * 解析目标桶并把非法值收敛为统一的参数错误，避免工具层依赖底层枚举异常文本。
     */
    private MemoryTargetBucket parseTarget(String rawTarget) {
        if (!StringUtils.hasText(rawTarget)) {
            throw new ToolArgumentException("缺少必填参数 target。", Map.of("field", "target"));
        }
        try {
            return MemoryTargetBucket.fromValue(rawTarget);
        } catch (IllegalArgumentException exception) {
            throw new ToolArgumentException("不支持的 target。", Map.of("field", "target", "value", rawTarget));
        }
    }

    /**
     * 按目标桶解析 `USER.md` 白名单类别，并对非法类别返回结构化参数错误。
     */
    private UserProfileCategory parseCategory(MemoryTargetBucket targetBucket, String rawCategory) {
        if (targetBucket != MemoryTargetBucket.USER_PROFILE) {
            return null;
        }
        if (!StringUtils.hasText(rawCategory)) {
            throw new ToolArgumentException("user_profile 必须提供白名单 category。", Map.of("field", "category"));
        }
        try {
            return UserProfileCategory.fromValue(rawCategory);
        } catch (IllegalArgumentException exception) {
            throw new ToolArgumentException("不支持的 user_profile category。", Map.of("field", "category", "value", rawCategory));
        }
    }

    /**
     * 收敛 remember 必填正文和触发原因校验，确保这些错误统一以 invalid_arguments 向上游暴露。
     */
    private void validateRememberInputs(String content, String triggerReason) {
        if (!StringUtils.hasText(content)) {
            throw new ToolArgumentException("缺少必填参数 content。", Map.of("field", "content"));
        }
        if (!StringUtils.hasText(triggerReason)) {
            throw new ToolArgumentException("缺少必填参数 reason。", Map.of("field", "reason"));
        }
    }

    /**
     * 解析 search scope，并把非法值收敛为统一参数错误，避免不同调用方看到底层实现差异。
     */
    private MemorySearchScope parseScope(String rawScope) {
        try {
            return MemorySearchScope.fromValue(rawScope);
        } catch (IllegalArgumentException exception) {
            throw new ToolArgumentException("不支持的 scope。", Map.of("field", "scope", "value", rawScope));
        }
    }

    /**
     * 统一发布 memory 相关摘要事件，保持事件命名、phase 与 trace 关联规则一致。
     */
    private void emitMemoryEvent(
            ToolExecutionContext executionContext,
            String eventType,
            RuntimeObservationLevel level,
            Map<String, Object> payload
    ) {
        runtimeObservationPublisher.emit(
                executionContext.traceContext(),
                eventType,
                RuntimeObservationPhase.TOOL,
                level,
                "LocalMemoryService",
                payload
        );
    }

    /**
     * 统一发布 memory 失败事件，并把异常类型和可选文本纳入结构化负载。
     */
    private void emitFailureEvent(ToolExecutionContext executionContext, String eventType, Exception exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exceptionType", exception.getClass().getSimpleName());
        if (StringUtils.hasText(exception.getMessage())) {
            payload.put("message", exception.getMessage());
        }
        emitMemoryEvent(executionContext, eventType, RuntimeObservationLevel.ERROR, Map.copyOf(payload));
    }

    /**
     * 为 remember 完成事件构造最小摘要，避免默认时间线直接输出完整 Markdown 内容。
     */
    private Map<String, Object> buildRememberPayload(MemoryWriteResult writeResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetBucket", writeResult.targetBucket());
        payload.put("relativePath", writeResult.relativePath());
        payload.put("duplicateSuppressed", writeResult.duplicateSuppressed());
        if (writeResult.persistedCategory() != null) {
            payload.put("persistedCategory", writeResult.persistedCategory());
        }
        return Map.copyOf(payload);
    }
}
