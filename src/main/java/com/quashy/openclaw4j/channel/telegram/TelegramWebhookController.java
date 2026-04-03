package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 Telegram webhook HTTP 入口，负责完成密钥校验、JSON 解析并把合法请求转交给 Telegram adapter 服务。
 */
@RestController
@ConditionalOnProperty(prefix = "openclaw.telegram", name = "enabled", havingValue = "true")
@RequestMapping("${openclaw.telegram.webhook-path:/api/telegram/webhook}")
public class TelegramWebhookController {

    /**
     * 当前 Telegram 渠道配置，只读取 webhook 鉴权所需的共享密钥。
     */
    private final OpenClawProperties.TelegramProperties telegramProperties;

    /**
     * Telegram adapter 的应用层编排服务，负责把合法 webhook update 转换成统一单聊入口调用。
     */
    private final TelegramWebhookService telegramWebhookService;

    /**
     * 通过集中配置和 Telegram adapter 服务保持控制器薄层，只承担 HTTP 协议相关职责。
     */
    @Autowired
    public TelegramWebhookController(OpenClawProperties properties, TelegramWebhookService telegramWebhookService) {
        this(properties.telegram(), telegramWebhookService);
    }

    /**
     * 允许测试直接注入 Telegram 配置切片，避免为了单个控制器测试装配整个应用配置对象。
     */
    TelegramWebhookController(OpenClawProperties.TelegramProperties telegramProperties, TelegramWebhookService telegramWebhookService) {
        this.telegramProperties = telegramProperties;
        this.telegramWebhookService = telegramWebhookService;
    }

    /**
     * 仅在 webhook secret 匹配时接受 Telegram update；鉴权失败直接拒绝，成功后统一返回 webhook 确认。
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(name = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody TelegramUpdate update
    ) {
        if (!isAuthorized(secretToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        telegramWebhookService.handle(update);
        return ResponseEntity.ok().build();
    }

    /**
     * 使用集中配置中的共享密钥校验 Telegram webhook 来源；若密钥未配置则视为当前入口不可用并拒绝请求。
     */
    private boolean isAuthorized(String secretToken) {
        return StringUtils.hasText(telegramProperties.webhookSecret())
                && telegramProperties.webhookSecret().equals(secretToken);
    }
}
