package com.quashy.openclaw4j.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring AI `ChatClient` 的模型实现，把现有 PoC 的模型直连方式收敛到统一抽象之后。
 */
@Component
public class ChatClientAgentModelClient implements AgentModelClient {

    /**
     * 复用 Spring AI 提供的同步聊天客户端，作为当前阶段唯一的模型调用实现。
     */
    private final ChatClient chatClient;

    /**
     * 延续当前仓库已经接入的 `ChatClient.Builder`，以最小改动把底层模型调用复用到统一 Agent 主链路中。
     */
    public ChatClientAgentModelClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 将统一提示词直接下发给当前模型客户端，保持本阶段“一次请求得到一次最终回复”的简单交互方式。
     */
    @Override
    public String generate(AgentPrompt prompt) {
        return chatClient.prompt(prompt.content()).call().content();
    }
}
