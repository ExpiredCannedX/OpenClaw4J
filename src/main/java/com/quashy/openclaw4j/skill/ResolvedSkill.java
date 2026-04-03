package com.quashy.openclaw4j.skill;

/**
 * 表示一次请求最终选中的 Skill 及其激活来源，供 Prompt 组装与 signal 输出共享同一事实来源。
 */
public record ResolvedSkill(
        /**
         * 标识最终命中的 Skill 名称，用于 signal payload 和 Prompt 标题。
         */
        String skillName,
        /**
         * 承载应被注入模型上下文的 Skill 正文，保持与 `SKILL.md` 解析结果一致。
         */
        String instruction,
        /**
         * 标识本次命中来自显式点名还是自动匹配，当前只允许 `explicit` 或 `auto`。
         */
        String activationMode
) {
}
