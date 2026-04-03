package com.quashy.openclaw4j.workspace;

import java.util.List;

/**
 * 表示一次 workspace 读取后的最小快照，明确区分静态规则、动态记忆和本地 Skill 文档三类上下文来源。
 */
public record WorkspaceSnapshot(
        /**
         * 按稳定顺序承载 `SOUL.md`、`SKILLS.md` 等静态规则内容，在上下文中优先级高于动态记忆。
         */
        List<WorkspaceFileContent> staticRules,
        /**
         * 承载 `USER.md` 和 `MEMORY.md` 等动态记忆内容，允许缺失但必须保持顺序稳定。
         */
        List<WorkspaceFileContent> dynamicMemories,
        /**
         * 承载从 `skills/` 目录递归发现的原始 `SKILL.md` 文档，供后续 resolver 再做解析与选择。
         */
        List<LocalSkillDocument> localSkillDocuments
) {

    /**
     * 在快照创建时复制集合，确保加载阶段确定下来的顺序不会被后续调用方意外修改。
     */
    public WorkspaceSnapshot {
        staticRules = List.copyOf(staticRules);
        dynamicMemories = List.copyOf(dynamicMemories);
        localSkillDocuments = List.copyOf(localSkillDocuments);
    }
}
