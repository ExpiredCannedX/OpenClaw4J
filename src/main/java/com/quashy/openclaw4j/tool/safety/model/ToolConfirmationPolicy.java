package com.quashy.openclaw4j.tool.safety.model;

/**
 * 描述工具请求在服务端是否需要命中显式确认态才能继续执行。
 */
public enum ToolConfirmationPolicy {

    /**
     * 表示该工具请求不需要额外确认，只要其他策略校验通过即可执行。
     */
    NEVER,

    /**
     * 表示该工具请求必须先命中服务端持久化的显式确认态，才能进入真实执行。
     */
    EXPLICIT
}

