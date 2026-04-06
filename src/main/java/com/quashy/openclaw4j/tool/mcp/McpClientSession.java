package com.quashy.openclaw4j.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * 抽象一次已初始化的 MCP 会话，使工具目录、调用路径与底层 SDK 生命周期解耦。
 */
public interface McpClientSession extends AutoCloseable {

    /**
     * 返回当前 server discovery 到的全部工具定义，供启动期构建统一工具目录。
     */
    List<McpDiscoveredTool> listTools();

    /**
     * 按远端原始工具名调用 MCP tool，并把成功结果归一化为内部统一 payload。
     */
    Map<String, Object> callTool(String toolName, Map<String, Object> arguments);

    /**
     * 关闭当前 session 持有的底层 transport 和进程资源，避免可选 server 降级后泄露句柄。
     */
    @Override
    void close();
}
