package com.quashy.openclaw4j.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 基于 Spring AI `ChatClient` 的模型实现，把规划决策解析与最终回复生成统一收敛到同一模型边界。
 */
@Component
public class ChatClientAgentModelClient implements AgentModelClient {

    /**
     * 复用 Spring AI 提供的同步聊天客户端，作为当前阶段唯一的模型调用实现。
     */
    private final ChatClient chatClient;

    /**
     * 负责把规划阶段的 JSON 决策文本解析为统一 Java 模型，避免上层直接处理原始字符串。
     */
    private final ObjectMapper objectMapper;

    /**
     * 延续当前仓库已经接入的 `ChatClient.Builder`，以最小改动把底层模型调用复用到统一 Agent 主链路中。
     */
    public ChatClientAgentModelClient(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 执行规划阶段模型调用，并把约定 JSON 解析成“直接回复”或“工具调用”两种结构化决策。
     */
    @Override
    public AgentModelDecision decideNextAction(AgentPrompt prompt) {
        return parseDecision(chatClient.prompt(prompt.content()).call().content());
    }

    /**
     * 执行最终回复阶段模型调用，沿用当前直接返回文本正文的最小集成方式。
     */
    @Override
    public String generateFinalReply(AgentPrompt prompt) {
        return chatClient.prompt(prompt.content()).call().content();
    }

    /**
     * 把模型输出的 JSON 决策文本解析为统一领域对象，并兼容常见的 Markdown 代码块包裹形式。
     */
    private AgentModelDecision parseDecision(String rawDecision) {
        if (!StringUtils.hasText(rawDecision)) {
            throw new IllegalStateException("模型未返回有效决策内容。");
        }
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(rawDecision));
            String decisionType = readRequiredText(root, "type");
            return switch (decisionType) {
                case "final_reply" -> new FinalReplyDecision(readRequiredText(root, "reply"));
                case "tool_call" -> new ToolCallDecision(
                        readRequiredText(root, "toolName"),
                        readArguments(root.path("arguments"))
                );
                default -> throw new IllegalStateException("未知模型决策类型: " + decisionType);
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型决策不是合法 JSON。", exception);
        }
    }

    /**
     * 去掉模型常见的 Markdown 代码块包装，减少 JSON 解析对输出样式波动的脆弱性。
     */
    private String stripMarkdownFence(String rawDecision) {
        String trimmed = rawDecision.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineBreak = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineBreak < 0 || lastFence <= firstLineBreak) {
            return trimmed;
        }
        return trimmed.substring(firstLineBreak + 1, lastFence).trim();
    }

    /**
     * 从 JSON 节点读取必填字符串字段，避免上层继续处理缺失或空白决策属性。
     */
    private String readRequiredText(JsonNode root, String fieldName) {
        JsonNode fieldNode = root.path(fieldName);
        if (!fieldNode.isTextual() || !StringUtils.hasText(fieldNode.asText())) {
            throw new IllegalStateException("模型决策缺少有效字段: " + fieldName);
        }
        return fieldNode.asText();
    }

    /**
     * 把 arguments 节点转换成普通 Map，允许无参工具省略该字段但拒绝非对象结构。
     */
    private Map<String, Object> readArguments(JsonNode argumentsNode) {
        if (argumentsNode.isMissingNode() || argumentsNode.isNull()) {
            return Map.of();
        }
        if (!argumentsNode.isObject()) {
            throw new IllegalStateException("模型工具参数必须是 JSON 对象。");
        }
        return objectMapper.convertValue(argumentsNode, new TypeReference<Map<String, Object>>() {
        });
    }
}
