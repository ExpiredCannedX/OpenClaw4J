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
     * 当核心文件存在时，加载器必须把 SOUL 归类为静态规则，并把 USER 与 MEMORY 归类为动态记忆。
     */
    @Test
    void shouldLoadStaticAndDynamicWorkspaceFiles(@TempDir Path workspaceRoot) throws IOException {
        Files.writeString(workspaceRoot.resolve("SOUL.md"), "保持克制");
        Files.writeString(workspaceRoot.resolve("USER.md"), "用户偏好");
        Files.writeString(workspaceRoot.resolve("MEMORY.md"), "长期记忆");
        FileWorkspaceLoader loader = new FileWorkspaceLoader(new OpenClawProperties(workspaceRoot.toString(), 6, "fallback"));

        WorkspaceSnapshot snapshot = loader.load();

        assertThat(snapshot.staticRules().fileName()).isEqualTo("SOUL.md");
        assertThat(snapshot.staticRules().content()).isEqualTo("保持克制");
        assertThat(snapshot.dynamicMemories())
                .extracting(WorkspaceFileContent::fileName, WorkspaceFileContent::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("USER.md", "用户偏好"),
                        org.assertj.core.groups.Tuple.tuple("MEMORY.md", "长期记忆")
                );
    }

    /**
     * 当可选动态文件缺失时，加载器必须回退为空内容，而不是让一次请求因为本地文件尚未初始化而失败。
     */
    @Test
    void shouldFallbackToEmptyContentWhenWorkspaceFilesAreMissing(@TempDir Path workspaceRoot) throws IOException {
        Files.writeString(workspaceRoot.resolve("SOUL.md"), "仅有规则");
        FileWorkspaceLoader loader = new FileWorkspaceLoader(new OpenClawProperties(workspaceRoot.toString(), 6, "fallback"));

        WorkspaceSnapshot snapshot = loader.load();

        assertThat(snapshot.staticRules().content()).isEqualTo("仅有规则");
        assertThat(snapshot.dynamicMemories())
                .extracting(WorkspaceFileContent::content)
                .containsExactly("", "");
    }
}
