package com.quashy.openclaw4j;

import com.quashy.openclaw4j.agent.port.AgentModelClient;
import com.quashy.openclaw4j.agent.prompt.AgentPrompt;
import com.quashy.openclaw4j.config.OpenClawProperties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 保留原有简单调试入口，但把模型调用细节下沉到统一抽象，避免控制器继续直接依赖底层 SDK。
 */
@RestController
@RequestMapping("/test")
public class ChatClientController {

    /**
     * 统一使用显式 logger，避免依赖 Lombok 生成字段导致构建链路差异时出现不可见问题。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatClientController.class);

    /**
     * 复用统一模型抽象，让旧调试入口和新单聊主链路共享相同的模型调用边界。
     */
    private final AgentModelClient agentModelClient;

    /**
     * 收敛调试入口需要的默认 prompt 等运行时配置，避免控制器继续持有不可覆盖的常量。
     */
    private final OpenClawProperties properties;

    /**
     * 复用统一的模型抽象，让调试入口和 direct-message 主链路共享同一套模型调用边界。
     */
    public ChatClientController(AgentModelClient agentModelClient, OpenClawProperties properties) {
        this.agentModelClient = agentModelClient;
        this.properties = properties;
    }

    /**
     * 提供兼容现有 PoC 的简单问答接口，同时验证统一模型抽象在旧入口上的可复用性。
     */
    @GetMapping("/simple/chat")
    public String simpleChat(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            prompt = properties.debug().defaultPrompt();
        }
        String content;
        try {
            content = agentModelClient.generateFinalReply(new AgentPrompt(prompt));
        } catch (Exception e) {
            LOGGER.error("simpleChat error", e);
            throw new RuntimeException(e.getMessage());
        }
        LOGGER.info("simpleChat --> \n prompt ={}, \n content = {}", prompt, content);
        return content;
    }

}
