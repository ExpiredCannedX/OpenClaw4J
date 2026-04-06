package com.quashy.openclaw4j.tool.safety.model;

/**
 * 标识策略层在执行前需要套用的参数级安全校验器类型。
 */
public enum ToolArgumentValidatorType {

    /**
     * 表示该工具当前不需要额外的参数级安全校验。
     */
    NONE,

    /**
     * 表示该工具需要走 filesystem 写能力专属的路径、敏感文件和递归/批量限制校验。
     */
    FILESYSTEM_WRITE
}

