package com.quashy.openclaw4j;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: Quashy
 * @Date: 2026/4/2/22:01
 * @Description:
 */
@Slf4j
@RestController
@RequestMapping("/test")
public class ChatClientController {

    private static final String DEFAULT_PROMPT = "你好，介绍下你自己！";

    /**
     * NewApi（OpenAi 兼容格式）的 ChatClient
     */
    private final ChatClient chatClient;

    // 使用如下的方式注入 ChatClient
    public ChatClientController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * ChatClient 简单调用
     */
    @GetMapping("/simple/chat")
    public String simpleChat(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            prompt = DEFAULT_PROMPT;
        }
        String content;
        try {
            content = chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            log.error("simpleChat error", e);
            throw new RuntimeException(e.getMessage());
        }
        log.info("simpleChat --> \n prompt ={}, \n content = {}", prompt, content);
        return content;
    }

}