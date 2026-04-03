package com.quashy.openclaw4j.workspace;

/**
 * 表示单个 workspace 文件的加载结果，让上下文组装层可以区分文件名与正文来源。
 */
public record WorkspaceFileContent(
        /**
         * 标识该上下文片段来自哪个 workspace 文件，便于后续调试和来源区分。
         */
        String fileName,
        /**
         * 记录文件的实际文本内容，缺失文件时允许为空字符串。
         */
        String content
) {
}
