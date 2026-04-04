package com.quashy.openclaw4j.observability.model;

/**
 * 标识运行事件所属的主链路阶段，确保控制台时间线和未来 Web UI 都能按稳定边界分组展示。
 */
public enum RuntimeObservationPhase {

    /**
     * 对应渠道消息进入统一单聊主链路的接入阶段。
     */
    INGRESS,

    /**
     * 对应幂等命中、并发复用等去重相关阶段。
     */
    IDEMPOTENCY,

    /**
     * 对应 Agent Core 的整体运行边界。
     */
    AGENT,

    /**
     * 对应 workspace 快照加载阶段。
     */
    WORKSPACE,

    /**
     * 对应 Skill 解析与注入判定阶段。
     */
    SKILL,

    /**
     * 对应模型规划决策阶段。
     */
    MODEL,

    /**
     * 对应同步工具执行阶段。
     */
    TOOL,

    /**
     * 对应最终回复正文生成与收敛阶段。
     */
    REPLY,

    /**
     * 对应渠道出站发送阶段。
     */
    OUTBOUND
}
