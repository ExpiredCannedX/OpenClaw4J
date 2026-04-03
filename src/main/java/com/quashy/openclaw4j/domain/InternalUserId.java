package com.quashy.openclaw4j.domain;

/**
 * 标识平台无关的内部用户身份，避免核心链路直接依赖任一渠道的原生用户 ID 语义。
 */
public record InternalUserId(
        /**
         * 承载内部用户唯一值，渠道侧原生用户 ID 不能越过映射层直接替代它。
         */
        String value
) {
}
