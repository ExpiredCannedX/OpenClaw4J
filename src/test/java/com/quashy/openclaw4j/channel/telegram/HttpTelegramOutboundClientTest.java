package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证 Telegram 出站客户端会按 Bot API 约定调用 `sendMessage`，避免渠道回复逻辑只停留在接口层抽象。
 */
class HttpTelegramOutboundClientTest {

    /**
     * 发送最终回复时必须命中 Telegram `sendMessage` 接口，并把原始 `chat.id` 与文本正文写入请求体。
     */
    @Test
    void shouldCallTelegramSendMessageApi() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        HttpTelegramOutboundClient client = new HttpTelegramOutboundClient(
                restClientBuilder,
                new OpenClawProperties.TelegramProperties(true, "bot-token", "secret-token", "/api/telegram/webhook", "https://example.com/api/telegram/webhook")
        );

        mockServer.expect(requestTo("https://api.telegram.org/botbot-token/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "chat_id": 2001,
                          "text": "已收到"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "result": {
                            "message_id": 1
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        client.sendMessage(new TelegramOutboundMessage(2001L, "已收到"));

        mockServer.verify();
    }
}
