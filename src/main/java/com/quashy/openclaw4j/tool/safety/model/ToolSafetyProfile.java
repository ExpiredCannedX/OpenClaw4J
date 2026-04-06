package com.quashy.openclaw4j.tool.safety.model;

import org.springframework.util.Assert;

/**
 * 描述只供服务端策略层消费的工具安全画像，避免把风险治理字段暴露给模型可见目录。
 */
public record ToolSafetyProfile(
        /**
         * 标识当前工具请求所属的服务端风险等级，是策略判定和审计摘要的核心语义。
         */
        ToolRiskLevel riskLevel,
        /**
         * 标识当前工具是否必须命中显式确认态才能进入真实执行。
         */
        ToolConfirmationPolicy confirmationPolicy,
        /**
         * 标识当前工具在执行前需要套用的参数级校验器类型。
         */
        ToolArgumentValidatorType validatorType
) {

    /**
     * 在画像创建时校验三类核心安全字段齐备，避免策略层收到半初始化画像。
     */
    public ToolSafetyProfile {
        Assert.notNull(riskLevel, "riskLevel must not be null");
        Assert.notNull(confirmationPolicy, "confirmationPolicy must not be null");
        Assert.notNull(validatorType, "validatorType must not be null");
    }

    /**
     * 返回默认只读画像，供未显式声明安全元数据的低风险工具平滑接入统一策略层。
     */
    public static ToolSafetyProfile readOnly() {
        return new ToolSafetyProfile(
                ToolRiskLevel.READ_ONLY,
                ToolConfirmationPolicy.NEVER,
                ToolArgumentValidatorType.NONE
        );
    }
}

