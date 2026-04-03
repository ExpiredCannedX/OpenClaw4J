package com.quashy.openclaw4j.skill;

import com.quashy.openclaw4j.workspace.LocalSkillDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责把 workspace 中的原始 `SKILL.md` 文档解析成当前主链路可消费的最小 Skill 定义，并对 richer front matter 保持兼容。
 */
@Component
public class SkillMarkdownParser {

    /**
     * 只在文档开头识别成对 `---` 包裹的 YAML front matter，避免误把正文中的分隔线解析为元数据。
     */
    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("\\A---\\R(.*?)\\R---\\R?(.*)\\z", Pattern.DOTALL);

    /**
     * 解析一份本地 Skill 文档，只提取 `name`、`description`、`keywords` 和正文，其余字段全部忽略。
     */
    public LocalSkillDefinition parse(LocalSkillDocument document) {
        Matcher matcher = FRONT_MATTER_PATTERN.matcher(document.content());
        if (!matcher.matches()) {
            return new LocalSkillDefinition("", "", List.of(), document.content().trim());
        }
        Map<String, Object> metadata = parseFrontMatter(matcher.group(1));
        return new LocalSkillDefinition(
                readString(metadata.get("name")),
                readString(metadata.get("description")),
                readKeywords(metadata.get("keywords")),
                matcher.group(2).trim()
        );
    }

    /**
     * 使用 YAML 解析 front matter，并在元数据异常时回退为空映射，避免单个 Skill 文件拖垮整次请求。
     */
    private Map<String, Object> parseFrontMatter(String frontMatter) {
        try {
            Object rawValue = new Yaml().load(frontMatter);
            if (rawValue instanceof Map<?, ?> rawMap) {
                return rawMap.entrySet().stream()
                        .filter(entry -> entry.getKey() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> String.valueOf(entry.getKey()),
                                Map.Entry::getValue
                        ));
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return Map.of();
    }

    /**
     * 只接受带文本内容的标量字段，避免把复杂 YAML 结构意外注入 Skill 标识或说明中。
     */
    private String readString(Object rawValue) {
        if (rawValue instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return "";
    }

    /**
     * 只提取字符串关键词，并兼容单字符串与 YAML 列表两种轻量写法。
     */
    private List<String> readKeywords(Object rawValue) {
        if (rawValue instanceof String keyword && StringUtils.hasText(keyword)) {
            return List.of(keyword.trim());
        }
        if (rawValue instanceof Iterable<?> iterable) {
            List<String> keywords = new ArrayList<>();
            for (Object item : iterable) {
                if (item instanceof String keyword && StringUtils.hasText(keyword)) {
                    keywords.add(keyword.trim());
                }
            }
            return List.copyOf(keywords);
        }
        return List.of();
    }
}
