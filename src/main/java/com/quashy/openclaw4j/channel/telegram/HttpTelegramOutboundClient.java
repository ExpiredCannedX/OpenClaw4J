package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 使用 Telegram Bot API 的 HTTP `sendMessage` 接口完成最终文本回复发送，是当前首版 Telegram 渠道的默认出站实现。
 */
@Component
@ConditionalOnProperty(prefix = "openclaw.telegram", name = "enabled", havingValue = "true")
public class HttpTelegramOutboundClient implements TelegramOutboundClient {

    /**
     * 面向 Telegram Bot API 的 HTTP 客户端，封装基础地址与请求发送行为。
     */
    private final RestClient restClient;

    /**
     * 当前 Telegram 渠道配置，只读取 bot token 等出站发送所需参数。
     */
    private final OpenClawProperties.TelegramProperties telegramProperties;

    /**
     * 以应用级集中配置初始化 HTTP 出站客户端，保证 Telegram token 与基础地址不会散落在调用点。
     */
    @Autowired
    public HttpTelegramOutboundClient(OpenClawProperties properties) {
        this(RestClient.builder(), properties.telegram());
    }

    /**
     * 允许测试注入可观测的 `RestClient.Builder`，从而在不访问真实 Telegram 的情况下验证出站请求。
     */
    HttpTelegramOutboundClient(RestClient.Builder restClientBuilder, OpenClawProperties.TelegramProperties telegramProperties) {
        this.restClient = restClientBuilder.baseUrl("https://api.telegram.org").build();
        this.telegramProperties = telegramProperties;
    }

    /**
     * 调用 Telegram `sendMessage` 发送最终文本回复；若 bot token 缺失则直接失败，避免静默丢消息。
     */
    @Override
    public void sendMessage(TelegramOutboundMessage outboundMessage) {
        if (!StringUtils.hasText(telegramProperties.botToken())) {
            throw new IllegalStateException("Telegram bot token 未配置，无法发送私聊回复。");
        }
        restClient.post()
                .uri("/bot{token}/sendMessage", telegramProperties.botToken())
                .body(new TelegramSendMessageRequest(outboundMessage.chatId(), outboundMessage.text()))
                .retrieve()
                .toBodilessEntity();
    }
}
