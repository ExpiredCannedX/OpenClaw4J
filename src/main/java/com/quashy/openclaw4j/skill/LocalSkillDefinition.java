package com.quashy.openclaw4j.skill;

import java.util.List;

/**
 * 表示从本地 `SKILL.md` 归一化得到的最小 Skill 定义，只保留当前匹配与注入链路真正需要的字段。
 */
public record LocalSkillDefinition(
        /**
         * 作为显式点名和 signal 标识使用的规范 Skill 名称；缺失时保持空字符串而不是中断请求。
         */
        String name,
        /**
         * 承载给调用方或后续调试查看的简要描述，当前阶段不参与匹配得分计算。
         */
        String description,
        /**
         * 定义自动匹配使用的关键词集合，只接受已经从 front matter 提取出的字符串值。
         */
        List<String> keywords,
        /**
         * 承载去掉 front matter 后的 Skill 指令正文，供 Prompt 组装时直接注入。
         */
        String instruction
) {

    /**
     * 在记录创建时复制集合，避免调用方后续修改关键词列表破坏解析结果的稳定性。
     */
    public LocalSkillDefinition {
        keywords = List.copyOf(keywords);
    }
}
