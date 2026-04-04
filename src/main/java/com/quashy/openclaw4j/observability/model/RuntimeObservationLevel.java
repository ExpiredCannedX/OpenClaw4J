package com.quashy.openclaw4j.observability.model;

/**
 * 描述运行事件的严重等级，便于 `ERRORS` 模式只保留足够重要的观测结果。
 */
public enum RuntimeObservationLevel {

    /**
     * 表示正常时间线中的普通阶段事件。
     */
    INFO,

    /**
     * 表示关键跳过、降级或需要开发者关注但尚未失败的事件。
     */
    WARN,

    /**
     * 表示已经发生异常、失败或主链路被迫中断的事件。
     */
    ERROR
}
