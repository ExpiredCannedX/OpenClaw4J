package com.quashy.openclaw4j.memory.model;

/**
 * 表示一次 memory.search 命中的结构化匹配结果，供工具和最终回复阶段回填相对路径、行号、预览片段与正向相关度分数。
 */
public record MemorySearchMatch(
        /**
         * 标识命中块所在的相对文件路径，便于调用方定位事实源。
         */
        String relativePath,
        /**
         * 标识命中块归属的目标桶值，帮助模型理解这是用户画像、长期记忆还是会话日志。
         */
        String targetBucket,
        /**
         * 标识命中块起始行号，供未来读取或人工定位时复用。
         */
        int lineStart,
        /**
         * 标识命中块结束行号，便于确定返回片段覆盖范围。
         */
        int lineEnd,
        /**
         * 承载适合暴露给模型和开发者的片段预览，而不是直接返回整个原始文件。
         */
        String previewSnippet,
        /**
         * 承载当前检索实现返回的正向相关度分数，约定值越大代表结果越相关。
         */
        double score
) {
}
