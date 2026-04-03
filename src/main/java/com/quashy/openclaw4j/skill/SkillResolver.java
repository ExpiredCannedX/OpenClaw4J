package com.quashy.openclaw4j.skill;

import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责在一次请求中按“显式优先、自动保守、歧义放弃”的策略解析至多一个本地 Skill。
 */
@Component
public class SkillResolver {

    /**
     * 优先识别 Markdown 链接标签中的 `$skill-name`，避免富文本包装吞掉显式点名语义。
     */
    private static final Pattern MARKDOWN_LINK_SKILL_PATTERN = Pattern.compile("\\[\\$([A-Za-z0-9][A-Za-z0-9_-]*)\\]\\([^\\)]*\\)");

    /**
     * 识别纯文本中的 `$skill-name` 显式指定，作为自动匹配之前的强约束输入。
     */
    private static final Pattern EXPLICIT_SKILL_PATTERN = Pattern.compile("\\$([A-Za-z0-9][A-Za-z0-9_-]*)");

    /**
     * 承担 `SKILL.md` front matter 解析职责，使 resolver 本身只关注优先级和匹配策略。
     */
    private final SkillMarkdownParser parser;

    /**
     * 通过显式依赖注入隔离解析器细节，让匹配策略测试不需要依赖文件系统。
     */
    public SkillResolver(SkillMarkdownParser parser) {
        this.parser = parser;
    }

    /**
     * 根据当前用户消息和本地 Skill 文档集合解析最终命中的 Skill；若不存在唯一安全结果则返回空。
     */
    public Optional<ResolvedSkill> resolve(String messageBody, List<LocalSkillDocument> localSkillDocuments) {
        List<LocalSkillDefinition> definitions = localSkillDocuments.stream()
                .map(parser::parse)
                .toList();
        Optional<ResolvedSkill> explicitSkill = resolveExplicitSkill(messageBody, definitions);
        if (explicitSkill.isPresent()) {
            return explicitSkill;
        }
        return resolveAutomatically(messageBody, definitions);
    }

    /**
     * 优先处理用户显式点名的 Skill，并在点名不存在时继续执行而不是抛出错误。
     */
    private Optional<ResolvedSkill> resolveExplicitSkill(String messageBody, List<LocalSkillDefinition> definitions) {
        Optional<String> explicitName = extractExplicitSkillName(messageBody);
        if (explicitName.isEmpty()) {
            return Optional.empty();
        }
        String normalizedExplicitName = normalize(explicitName.get());
        return definitions.stream()
                .filter(definition -> StringUtils.hasText(definition.name()))
                .filter(definition -> normalize(definition.name()).equals(normalizedExplicitName))
                .findFirst()
                .map(definition -> new ResolvedSkill(definition.name(), definition.instruction(), "explicit"));
    }

    /**
     * 只在没有显式指定时做关键词自动匹配，并且只有唯一最高分命中才返回 Skill。
     */
    private Optional<ResolvedSkill> resolveAutomatically(String messageBody, List<LocalSkillDefinition> definitions) {
        String normalizedMessage = normalize(messageBody);
        int highestScore = 0;
        List<LocalSkillDefinition> strongestMatches = new ArrayList<>();
        for (LocalSkillDefinition definition : definitions) {
            int score = scoreAutomaticMatch(normalizedMessage, definition);
            if (score <= 0) {
                continue;
            }
            if (score > highestScore) {
                highestScore = score;
                strongestMatches = new ArrayList<>(List.of(definition));
                continue;
            }
            if (score == highestScore) {
                strongestMatches.add(definition);
            }
        }
        if (strongestMatches.size() != 1) {
            return Optional.empty();
        }
        LocalSkillDefinition matchedSkill = strongestMatches.getFirst();
        return Optional.of(new ResolvedSkill(matchedSkill.name(), matchedSkill.instruction(), "auto"));
    }

    /**
     * 用匹配到的关键词数量作为保守得分，既可解释又足够支撑当前单 Skill 选择约束。
     */
    private int scoreAutomaticMatch(String normalizedMessage, LocalSkillDefinition definition) {
        int score = 0;
        for (String keyword : definition.keywords()) {
            String normalizedKeyword = normalize(keyword);
            if (StringUtils.hasText(normalizedKeyword) && normalizedMessage.contains(normalizedKeyword)) {
                score++;
            }
        }
        return score;
    }

    /**
     * 先检查 Markdown 链接标签，再退化到普通 `$skill-name`，避免同一段文本被重复提取两次。
     */
    private Optional<String> extractExplicitSkillName(String messageBody) {
        Matcher markdownMatcher = MARKDOWN_LINK_SKILL_PATTERN.matcher(messageBody);
        if (markdownMatcher.find()) {
            return Optional.of(markdownMatcher.group(1));
        }
        Matcher plainMatcher = EXPLICIT_SKILL_PATTERN.matcher(messageBody);
        if (plainMatcher.find()) {
            return Optional.of(plainMatcher.group(1));
        }
        return Optional.empty();
    }

    /**
     * 对大小写和连字符、下划线、空格差异做轻量归一化，保持规则可测试且不引入更激进的语义猜测。
     */
    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[-_\\s]+", " ")
                .trim();
    }
}
