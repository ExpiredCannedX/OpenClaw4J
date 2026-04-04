package com.quashy.openclaw4j.memory;

import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.memory.index.SqliteMemoryIndexer;
import com.quashy.openclaw4j.memory.store.MarkdownMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * 负责装配 memory V1 所需的底层 store 与 indexer，使业务层无需自己解析 workspace 和索引文件路径。
 */
@Configuration(proxyBeanMethods = false)
public class MemoryConfiguration {

    /**
     * 创建基于当前 workspace 根目录的 Markdown memory store，保持文件事实源写入入口唯一。
     */
    @Bean
    public MarkdownMemoryStore markdownMemoryStore(OpenClawProperties properties) {
        return new MarkdownMemoryStore(Path.of(properties.workspaceRoot()), Clock.systemDefaultZone());
    }

    /**
     * 创建基于当前 workspace 和配置索引路径的 SQLite indexer，保持检索层与事实源布局一致。
     */
    @Bean
    public SqliteMemoryIndexer sqliteMemoryIndexer(OpenClawProperties properties) {
        Path workspaceRoot = Path.of(properties.workspaceRoot());
        Path configuredIndexPath = Path.of(properties.memory().indexFile());
        Path resolvedIndexPath = configuredIndexPath.isAbsolute()
                ? configuredIndexPath
                : workspaceRoot.resolve(configuredIndexPath);
        return new SqliteMemoryIndexer(workspaceRoot, resolvedIndexPath, Clock.systemDefaultZone());
    }
}
