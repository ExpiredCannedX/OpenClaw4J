package com.quashy.openclaw4j.observability.model;

/**
 * 定义开发者可见运行期观测的输出档位，用于在“关闭、错误优先、摘要时间线、详细预览”之间切换信息边界。
 */
public enum RuntimeObservationMode {

    /**
     * 完全关闭开发者可见的运行事件输出，但不影响业务主链路执行。
     */
    OFF,

    /**
     * 只保留失败、异常和关键跳过事件，适合低噪音排障场景。
     */
    ERRORS,

    /**
     * 输出完整时间线，但仅包含摘要级元数据，是默认推荐模式。
     */
    TIMELINE,

    /**
     * 在时间线基础上追加截断后的调试预览字段，供深度联调时使用。
     */
    VERBOSE
}
