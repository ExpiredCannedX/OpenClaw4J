package com.quashy.openclaw4j.workspace;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 workspace 基础加载器在文件齐全和缺失时都能输出稳定的上下文快照。
 */
class FileWorkspaceLoaderTest {

    /**
     * 当核心文件和本地 Skill 文档都存在时，加载器必须稳定区分静态规则、动态记忆和本地 Skill 来源。
     */
    @Test
    void shouldLoadWorkspaceFilesAndLocalSkills(@TempDir Path workspaceRoot) throws IOException {
        Files.writeString(workspaceRoot.resolve("SOUL.md"), "保持克制");
        Files.writeString(workspaceRoot.resolve("SKILLS.md"), "需要时选择本地 Skill");
        Files.writeString(workspaceRoot.resolve("USER.md"), "用户偏好");
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), "长期记忆");
        Path firstSkillDirectory = Files.createDirectories(workspaceRoot.resolve("skills").resolve("a-review"));
        Path secondSkillDirectory = Files.createDirectories(workspaceRoot.resolve("skills").resolve("z-refactor"));
        Files.writeString(firstSkillDirectory.resolve("SKILL.md"), "review skill");
        Files.writeString(secondSkillDirectory.resolve("SKILL.md"), "refactor skill");
        FileWorkspaceLoader loader = new FileWorkspaceLoader(new OpenClawProperties(
                workspaceRoot.toString(),
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
        ));

        WorkspaceSnapshot snapshot = loader.load();

        assertThat(snapshot.staticRules())
                .extracting(WorkspaceFileContent::fileName, WorkspaceFileContent::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SOUL.md", "保持克制"),
                        org.assertj.core.groups.Tuple.tuple("SKILLS.md", "需要时选择本地 Skill")
                );
        assertThat(snapshot.dynamicMemories())
                .extracting(WorkspaceFileContent::fileName, WorkspaceFileContent::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("USER.md", "用户偏好"),
                        org.assertj.core.groups.Tuple.tuple("MEMORY.md", "长期记忆")
                );
        assertThat(snapshot.localSkillDocuments())
                .extracting(LocalSkillDocument::relativePath, LocalSkillDocument::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("skills/a-review/SKILL.md", "review skill"),
                        org.assertj.core.groups.Tuple.tuple("skills/z-refactor/SKILL.md", "refactor skill")
                );
    }

    /**
     * 当可选文件和本地 Skill 目录缺失时，加载器必须回退为空内容或空集合，而不是让一次请求失败。
     */
    @Test
    void shouldFallbackToEmptyContentWhenWorkspaceFilesAreMissing(@TempDir Path workspaceRoot) throws IOException {
        Files.writeString(workspaceRoot.resolve("SOUL.md"), "仅有规则");
        FileWorkspaceLoader loader = new FileWorkspaceLoader(new OpenClawProperties(
                workspaceRoot.toString(),
                6,
                "fallback",
                new OpenClawProperties.DebugProperties("你好，介绍下你自己！"),
                new OpenClawProperties.TelegramProperties(false, "", "", "/api/telegram/webhook", "")
        ));

        WorkspaceSnapshot snapshot = loader.load();

        assertThat(snapshot.staticRules())
                .extracting(WorkspaceFileContent::content)
                .containsExactly("仅有规则", "");
        assertThat(snapshot.dynamicMemories())
                .extracting(WorkspaceFileContent::content)
                .containsExactly("", "");
        assertThat(snapshot.localSkillDocuments()).isEmpty();
    }
}
