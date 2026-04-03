package com.quashy.openclaw4j.agent;

/**
 * 表示传递给模型层的最小提示词载体，避免调用方直接暴露底层模型 SDK 的输入细节。
 */
public record AgentPrompt(
        /**
         * 承载已经完成上下文组装的最终提示词正文，模型层只消费这个稳定字符串接口。
         */
        String content
) {
}
