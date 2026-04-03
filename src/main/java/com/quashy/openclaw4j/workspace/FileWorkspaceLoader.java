package com.quashy.openclaw4j.workspace;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 基于本地文件系统加载 workspace 核心文件，用最小只读能力支撑当前单聊主链路。
 */
@Component
public class FileWorkspaceLoader implements WorkspaceLoader {

    /**
     * 提供 workspace 根路径等加载配置，使文件读取逻辑不直接依赖硬编码路径。
     */
    private final OpenClawProperties properties;

    /**
     * 通过集中配置注入 workspace 根目录，避免加载器自行推断路径导致环境差异难以排查。
     */
    public FileWorkspaceLoader(OpenClawProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取 `SOUL.md`、`USER.md` 和 `MEMORY.md`，其中缺失文件统一回退为空内容，确保请求能继续执行。
     */
    @Override
    public WorkspaceSnapshot load() {
        Path workspaceRoot = Path.of(properties.workspaceRoot());
        WorkspaceFileContent staticRules = new WorkspaceFileContent("SOUL.md", readOrEmpty(workspaceRoot.resolve("SOUL.md")));
        List<WorkspaceFileContent> dynamicMemories = List.of(
                new WorkspaceFileContent("USER.md", readOrEmpty(workspaceRoot.resolve("USER.md"))),
                new WorkspaceFileContent("MEMORY.md", readOrEmpty(workspaceRoot.resolve("MEMORY.md")))
        );
        return new WorkspaceSnapshot(staticRules, dynamicMemories);
    }

    /**
     * 以空字符串作为缺失或读取失败时的回退值，使 workspace 尚未初始化的场景不会中断一次对话。
     */
    private String readOrEmpty(Path filePath) {
        try {
            return Files.exists(filePath) ? Files.readString(filePath) : "";
        } catch (IOException exception) {
            return "";
        }
    }
}
