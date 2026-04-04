package com.quashy.openclaw4j.memory.model;

import java.util.Arrays;

/**
 * 定义 memory V1 支持的三个目标桶，并统一维护对外工具参数值与内部文件落点语义的映射。
 */
public enum MemoryTargetBucket {

    /**
     * 表示应写入单用户稳定画像文件 `USER.md` 的目标桶。
     */
    USER_PROFILE("user_profile", "USER.md"),

    /**
     * 表示应写入跨会话长期事实文件 `MEMORY.md` 的目标桶。
     */
    LONG_TERM("long_term", "MEMORY.md"),

    /**
     * 表示应写入按日追加会话日志 `memory/YYYY-MM-DD.md` 的目标桶。
     */
    SESSION_LOG("session_log", null);

    /**
     * 保存暴露给模型和工具调用方的稳定字符串值，避免直接依赖枚举常量名。
     */
    private final String value;

    /**
     * 保存固定文件目标桶对应的相对文件名；session 日志按日期动态生成，因此此处允许为空。
     */
    private final String defaultRelativePath;

    /**
     * 通过显式值对象固定工具协议和文件语义，避免后续改名时破坏外部调用兼容性。
     */
    MemoryTargetBucket(String value, String defaultRelativePath) {
        this.value = value;
        this.defaultRelativePath = defaultRelativePath;
    }

    /**
     * 返回暴露给工具协议和结构化结果的稳定目标桶字符串值。
     */
    public String value() {
        return value;
    }

    /**
     * 返回固定文件目标桶的默认相对路径；仅 `session_log` 需要由调用方按日期单独解析。
     */
    public String defaultRelativePath() {
        return defaultRelativePath;
    }

    /**
     * 根据工具参数解析目标桶，并在输入不受支持时抛出稳定错误，避免静默降级到其他文件。
     */
    public static MemoryTargetBucket fromValue(String rawValue) {
        return Arrays.stream(values())
                .filter(bucket -> bucket.value.equals(rawValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported target bucket: " + rawValue));
    }
}
