package com.quashy.openclaw4j.workspace;

import java.util.List;

/**
 * 表示一次 workspace 读取后的最小快照，明确区分静态规则和动态记忆两类上下文来源。
 */
public record WorkspaceSnapshot(
        /**
         * 承载 `SOUL.md` 等静态规则内容，在上下文中优先级高于动态记忆。
         */
        WorkspaceFileContent staticRules,
        /**
         * 承载 `USER.md` 和 `MEMORY.md` 等动态记忆内容，允许缺失但必须保持顺序稳定。
         */
        List<WorkspaceFileContent> dynamicMemories
) {
}
