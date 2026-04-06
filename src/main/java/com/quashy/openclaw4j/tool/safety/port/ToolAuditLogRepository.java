package com.quashy.openclaw4j.tool.safety.port;

import com.quashy.openclaw4j.tool.safety.audit.ToolAuditLogEntry;

/**
 * 抽象工具安全审计日志的追加式写入边界，避免策略层直接处理 JDBC 细节。
 */
public interface ToolAuditLogRepository {

    /**
     * 追加一条结构化审计事件，保证策略判定、确认状态和执行结果都能被后续追踪。
     */
    void appendAuditLog(ToolAuditLogEntry entry);
}

