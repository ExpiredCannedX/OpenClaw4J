package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.domain.ReplyEnvelope;

/**
 * 统一封装 Agent Core 对外暴露的最小入口，让渠道层只需要提交标准化请求并获取结构化回复。
 */
public interface AgentFacade {

    /**
     * 执行一次最小 `Load -> Think -> Reply` 流程，并返回渠道可直接消费的回复结果。
     */
    ReplyEnvelope reply(AgentRequest request);
}
