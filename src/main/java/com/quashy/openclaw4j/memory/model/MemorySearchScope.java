package com.quashy.openclaw4j.memory.model;

import java.util.Arrays;

/**
 * 定义 memory.search 在 V1 支持的检索范围，使调用方可以显式控制是否限制到当前会话。
 */
public enum MemorySearchScope {

    /**
     * 表示在全部记忆桶上检索，包括 `USER.md`、`MEMORY.md` 和 `memory/*.md`。
     */
    ALL("all"),

    /**
     * 表示仅在 `USER.md` 上检索用户稳定画像。
     */
    USER_PROFILE("user_profile"),

    /**
     * 表示仅在 `MEMORY.md` 上检索长期事实。
     */
    LONG_TERM("long_term"),

    /**
     * 表示仅返回与当前内部会话关联的 session 日志块。
     */
    SESSION("session");

    /**
     * 保存暴露给工具协议的稳定 scope 字符串，避免直接耦合枚举常量名。
     */
    private final String value;

    /**
     * 用显式字符串值固定外部协议，降低后续重构对模型决策提示的影响。
     */
    MemorySearchScope(String value) {
        this.value = value;
    }

    /**
     * 返回暴露给工具协议和结果载荷的稳定 scope 值。
     */
    public String value() {
        return value;
    }

    /**
     * 根据工具参数解析 scope，并在缺省场景回退到 `all` 保持最小可用行为。
     */
    public static MemorySearchScope fromValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return ALL;
        }
        return Arrays.stream(values())
                .filter(scope -> scope.value.equals(rawValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported memory search scope: " + rawValue));
    }
}
