package com.quashy.openclaw4j.memory.model;

/**
 * 描述一次记忆写入的结构化结果，使工具层可以直接返回目标桶、文件位置和去重状态。
 */
public record MemoryWriteResult(
        /**
         * 标识本次写入最终归属的目标桶值，供工具结果和调用方直接消费。
         */
        String targetBucket,
        /**
         * 标识实际写入或命中的相对文件路径，便于调用方理解记忆被持久化到哪里。
         */
        String relativePath,
        /**
         * 标识 `USER.md` 写入时真正持久化的类别值，非画像写入允许为空。
         */
        String persistedCategory,
        /**
         * 标识本次是否因为命中重复内容而跳过了实际写入。
         */
        boolean duplicateSuppressed
) {
}
