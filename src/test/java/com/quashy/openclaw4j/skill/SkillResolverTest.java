package com.quashy.openclaw4j.skill;

import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证本地 Skill 解析优先级遵循“显式优先、自动保守、歧义放弃”的最小策略。
 */
class SkillResolverTest {

    /**
     * 当用户通过 `$skill-name` 显式点名 Skill 时，resolver 必须直接命中，避免被自动匹配结果覆盖。
     */
    @Test
    void shouldResolveExplicitSkillFromDollarSyntax() {
        SkillResolver resolver = new SkillResolver(new SkillMarkdownParser());

        ResolvedSkill resolvedSkill = resolver.resolve(
                        "请使用 $code-review 帮我检查这个 PR",
                        List.of(skillDocument("code-review", List.of("pull request"), "先看风险"))
                )
                .orElseThrow();

        assertThat(resolvedSkill.skillName()).isEqualTo("code-review");
        assertThat(resolvedSkill.activationMode()).isEqualTo("explicit");
        assertThat(resolvedSkill.instruction()).isEqualTo("先看风险");
    }

    /**
     * 当显式指定被包装在 Markdown 链接标签里时，resolver 仍然必须识别 `$skill-name`，避免渠道富文本破坏点名语义。
     */
    @Test
    void shouldResolveExplicitSkillFromMarkdownLinkLabel() {
        SkillResolver resolver = new SkillResolver(new SkillMarkdownParser());

        ResolvedSkill resolvedSkill = resolver.resolve(
                        "请按 [$code-review](https://example.test/skills/code-review) 的流程执行",
                        List.of(skillDocument("code-review", List.of("pull request"), "先看风险"))
                )
                .orElseThrow();

        assertThat(resolvedSkill.skillName()).isEqualTo("code-review");
        assertThat(resolvedSkill.activationMode()).isEqualTo("explicit");
    }

    /**
     * 当显式指定的 Skill 不存在时，请求必须继续执行且不抛错，避免把用户的点名写法变成失败入口。
     */
    @Test
    void shouldNotResolveSkillWhenExplicitlyNamedSkillDoesNotExist() {
        SkillResolver resolver = new SkillResolver(new SkillMarkdownParser());

        assertThat(resolver.resolve(
                "请使用 $missing-skill 帮我处理",
                List.of(skillDocument("code-review", List.of("pull request"), "先看风险"))
        )).isEmpty();
    }

    /**
     * 自动匹配必须支持大小写和分隔符轻量归一化，否则 `code review` 与 `code-review` 的自然写法差异会导致误失配。
     */
    @Test
    void shouldResolveSingleAutomaticSkillAfterNormalization() {
        SkillResolver resolver = new SkillResolver(new SkillMarkdownParser());

        ResolvedSkill resolvedSkill = resolver.resolve(
                        "请帮我做 CODE REVIEW",
                        List.of(skillDocument("code-review", List.of("code-review"), "先看风险"))
                )
                .orElseThrow();

        assertThat(resolvedSkill.skillName()).isEqualTo("code-review");
        assertThat(resolvedSkill.activationMode()).isEqualTo("auto");
    }

    /**
     * 多个 Skill 以同等强度命中时必须放弃自动选择，避免把不可解释的结果注入主链路。
     */
    @Test
    void shouldNotResolveSkillWhenAutomaticMatchingIsAmbiguous() {
        SkillResolver resolver = new SkillResolver(new SkillMarkdownParser());

        assertThat(resolver.resolve(
                "请帮我做 incident response",
                List.of(
                        skillDocument("incident-review", List.of("incident response"), "审查事故"),
                        skillDocument("incident-retro", List.of("incident_response"), "复盘事故")
                )
        )).isEmpty();
    }

    /**
     * 统一构造最小本地 Skill 文档，避免测试把关注点浪费在重复的 front matter 模板上。
     */
    private LocalSkillDocument skillDocument(String name, List<String> keywords, String instruction) {
        String keywordBlock = keywords.stream()
                .map(keyword -> "  - " + keyword)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new LocalSkillDocument(
                "skills/" + name + "/SKILL.md",
                """
                ---
                name: %s
                description: %s description
                keywords:
                %s
                ---
                %s
                """.formatted(name, name, keywordBlock, instruction)
        );
    }
}
