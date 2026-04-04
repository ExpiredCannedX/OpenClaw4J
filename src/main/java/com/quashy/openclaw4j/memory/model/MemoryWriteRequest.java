package com.quashy.openclaw4j.memory.model;

import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import org.springframework.util.Assert;

/**
 * 描述一次已经完成目标桶与类别解析的 memory 写入请求，供 store 与 service 共享稳定输入模型。
 */
public record MemoryWriteRequest(
        /**
         * 指定本次写入应落入的目标桶，决定最终 Markdown 文件归属。
         */
        MemoryTargetBucket targetBucket,
        /**
         * 指定 `USER.md` 写入时的白名单类别；非 `user_profile` 目标桶允许为空。
         */
        UserProfileCategory category,
        /**
         * 承载需要沉淀到本地记忆中的正文内容。
         */
        String content,
        /**
         * 承载当前写入的触发原因，用于 provenance 留痕与后续人工审计。
         */
        String triggerReason,
        /**
         * 承载可选置信度，用于记录模型或上层流程对该记忆的稳定性判断。
         */
        Double confidence,
        /**
         * 承载由系统生成的运行时上下文，供 store 写入 provenance 时读取系统事实。
         */
        ToolExecutionContext executionContext
) {

    /**
     * 在请求创建时做最小必填校验，避免下游存储组件收到缺少正文或上下文的半成品请求。
     */
    public MemoryWriteRequest {
        Assert.notNull(targetBucket, "targetBucket must not be null");
        Assert.hasText(content, "content must not be blank");
        Assert.hasText(triggerReason, "triggerReason must not be blank");
        Assert.notNull(executionContext, "executionContext must not be null");
    }
}
