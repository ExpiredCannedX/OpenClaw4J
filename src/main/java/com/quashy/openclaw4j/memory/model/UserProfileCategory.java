package com.quashy.openclaw4j.memory.model;

import java.util.Arrays;

/**
 * 定义允许自动写入 `USER.md` 的白名单类别，并为每个类别提供稳定节标题。
 */
public enum UserProfileCategory {

    /**
     * 表示用户偏好的称呼或稳定称谓，应写入 `USER.md` 的“称呼”节。
     */
    PREFERRED_NAME("preferred_name", "称呼"),

    /**
     * 表示明确且跨会话稳定的偏好，应写入 `USER.md` 的“偏好”节。
     */
    PREFERENCE("preference", "偏好"),

    /**
     * 表示长期习惯或固定工作方式，应写入 `USER.md` 的“习惯”节。
     */
    HABIT("habit", "习惯"),

    /**
     * 表示应尽量规避的禁忌或排斥项，应写入 `USER.md` 的“禁忌”节。
     */
    TABOO("taboo", "禁忌"),

    /**
     * 表示稳定约束或长期限制条件，应写入 `USER.md` 的“稳定约束”节。
     */
    CONSTRAINT("constraint", "稳定约束");

    /**
     * 保存暴露给工具协议的白名单类别值，避免直接把枚举名泄露为外部契约。
     */
    private final String value;

    /**
     * 保存写入 `USER.md` 时应使用的节标题，确保文件可读性与写入位置稳定。
     */
    private final String sectionTitle;

    /**
     * 用显式值和标题绑定类别语义，避免调用方散落字符串常量。
     */
    UserProfileCategory(String value, String sectionTitle) {
        this.value = value;
        this.sectionTitle = sectionTitle;
    }

    /**
     * 返回暴露给工具协议和结构化结果的稳定类别值。
     */
    public String value() {
        return value;
    }

    /**
     * 返回 `USER.md` 中与当前类别对应的稳定节标题。
     */
    public String sectionTitle() {
        return sectionTitle;
    }

    /**
     * 根据工具参数解析用户画像类别，并在不属于白名单时抛出稳定错误。
     */
    public static UserProfileCategory fromValue(String rawValue) {
        return Arrays.stream(values())
                .filter(category -> category.value.equals(rawValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported user profile category: " + rawValue));
    }
}
