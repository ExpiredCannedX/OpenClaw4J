package com.quashy.openclaw4j.channel.dm;

import com.quashy.openclaw4j.agent.model.ReplyEnvelope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露开发阶段使用的 direct-message HTTP 入口，用于在接入真实 IM 渠道前先验证核心单聊主链路。
 */
@RestController
@RequestMapping("/api/direct-messages")
public class DirectMessageController {

    /**
     * 承接 direct-message 的应用层编排，控制器只负责 HTTP 入参与出参适配。
     */
    private final DirectMessageService directMessageService;

    /**
     * 通过应用服务承接 direct-message 主链路，控制器本身只保留 HTTP 协议适配职责。
     */
    public DirectMessageController(DirectMessageService directMessageService) {
        this.directMessageService = directMessageService;
    }

    /**
     * 接收开发用单聊请求并返回最终一次性回复，确保当前 change 的入口行为与未来渠道适配层保持一致。
     */
    @PostMapping
    public ReplyEnvelope directMessage(@RequestBody DirectMessageRequest request) {
        return directMessageService.handle(request.toCommand());
    }
}
