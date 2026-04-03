package com.quashy.openclaw4j.skill;

import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 `SKILL.md` 解析器只提取当前变更需要的规范字段，并对未知 front matter 保持兼容。
 */
class SkillMarkdownParserTest {

    /**
     * richer front matter 中的未知字段不应导致解析失败，否则 workspace 里的本地 Skill 会因为额外元数据无法复用。
     */
    @Test
    void shouldParseRecognizedFieldsAndIgnoreUnknownMetadata() {
        SkillMarkdownParser parser = new SkillMarkdownParser();

        LocalSkillDefinition definition = parser.parse(new LocalSkillDocument(
                "skills/code-review/SKILL.md",
                """
                ---
                name: code-review
                description: 审查改动风险
                keywords:
                  - code-review
                  - pull request
                owner: platform
                ---
                请先给出风险，再给出修复建议。
                """
        ));

        assertThat(definition.name()).isEqualTo("code-review");
        assertThat(definition.description()).isEqualTo("审查改动风险");
        assertThat(definition.keywords()).containsExactly("code-review", "pull request");
        assertThat(definition.instruction()).isEqualTo("请先给出风险，再给出修复建议。");
    }
}
