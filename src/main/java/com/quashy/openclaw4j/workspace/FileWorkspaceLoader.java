package com.quashy.openclaw4j.workspace;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
     * 读取核心 workspace 文件并发现本地 Skill 文档，其中缺失内容统一回退为空，确保请求能继续执行。
     */
    @Override
    public WorkspaceSnapshot load() {
        Path workspaceRoot = Path.of(properties.workspaceRoot());
        return new WorkspaceSnapshot(
                loadStaticRules(workspaceRoot),
                loadDynamicMemories(workspaceRoot),
                loadLocalSkillDocuments(workspaceRoot)
        );
    }

    /**
     * 以固定顺序加载静态规则文件，保证 Prompt 组装阶段不会因为文件系统遍历顺序变化而抖动。
     */
    private List<WorkspaceFileContent> loadStaticRules(Path workspaceRoot) {
        return List.of(
                loadWorkspaceFile(workspaceRoot, "SOUL.md"),
                loadWorkspaceFile(workspaceRoot, "SKILLS.md")
        );
    }

    /**
     * 以固定顺序加载动态记忆文件，让调用方无需自行关心 `USER.md` 与 `MEMORY.md` 的拼接顺序。
     */
    private List<WorkspaceFileContent> loadDynamicMemories(Path workspaceRoot) {
        return List.of(
                loadWorkspaceFile(workspaceRoot, "USER.md"),
                loadWorkspaceFile(workspaceRoot, "MEMORY.md")
        );
    }

    /**
     * 递归发现 `skills/` 目录下的 `SKILL.md`，并按相对路径排序，保证 Skill resolver 面对稳定输入。
     */
    private List<LocalSkillDocument> loadLocalSkillDocuments(Path workspaceRoot) {
        Path skillsRoot = workspaceRoot.resolve("skills");
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        try (Stream<Path> fileStream = Files.walk(skillsRoot)) {
            return fileStream
                    .filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> toRelativePath(workspaceRoot, path)))
                    .map(path -> new LocalSkillDocument(toRelativePath(workspaceRoot, path), readOrEmpty(path)))
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * 统一构造 workspace 文件内容对象，避免各类核心文件在创建时重复拼装文件名与正文。
     */
    private WorkspaceFileContent loadWorkspaceFile(Path workspaceRoot, String fileName) {
        return new WorkspaceFileContent(fileName, readOrEmpty(workspaceRoot.resolve(fileName)));
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

    /**
     * 统一把相对路径标准化为正斜杠形式，避免不同平台下的路径分隔符影响排序与测试断言。
     */
    private String toRelativePath(Path workspaceRoot, Path filePath) {
        return workspaceRoot.relativize(filePath).toString().replace('\\', '/');
    }
}
