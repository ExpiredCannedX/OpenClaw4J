package com.quashy.openclaw4j.workspace;

/**
 * 负责从配置的 workspace 根目录读取核心上下文文件，为 Agent 每次运行提供一致的输入来源。
 */
public interface WorkspaceLoader {

    /**
     * 加载当前请求所需的 workspace 快照，并对缺失文件做受控回退，避免把文件系统细节泄漏给上层。
     */
    WorkspaceSnapshot load();
}
