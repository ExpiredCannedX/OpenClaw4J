package com.quashy.openclaw4j.tool.mcp;

/**
 * 抽象 MCP client session 的创建入口，使目录初始化流程不直接依赖具体 SDK 或 transport 细节。
 */
@FunctionalInterface
public interface McpClientFactory {

    /**
     * 为指定 server alias 创建一个已准备好做 discovery 与 invocation 的 MCP session。
     */
    McpClientSession create(String serverAlias);
}
