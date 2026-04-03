package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.config.OpenClawProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Telegram webhook 控制器只负责协议层鉴权和请求转发，避免把渠道密钥校验散落到核心服务。
 */
class TelegramWebhookControllerTest {

    /**
     * 缺失或错误的 Telegram secret token 必须在控制器层直接拒绝，避免未鉴权流量进入单聊主链路。
     */
    @Test
    void shouldRejectWebhookRequestWhenSecretTokenIsMissingOrInvalid() throws Exception {
        TelegramWebhookService telegramWebhookService = mock(TelegramWebhookService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TelegramWebhookController(
                new OpenClawProperties.TelegramProperties(true, "bot-token", "expected-secret", "/api/telegram/webhook", "https://example.com/api/telegram/webhook"),
                telegramWebhookService
        )).build();

        mockMvc.perform(post("/api/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1001}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Telegram-Bot-Api-Secret-Token", "wrong-secret")
                        .content("{\"update_id\":1001}"))
                .andExpect(status().isUnauthorized());

        verify(telegramWebhookService, never()).handle(any());
    }

    /**
     * 鉴权通过的 Telegram webhook 请求必须被解析成 update DTO 并转交给 Telegram adapter 服务继续处理。
     */
    @Test
    void shouldForwardAuthorizedWebhookRequestToTelegramService() throws Exception {
        TelegramWebhookService telegramWebhookService = mock(TelegramWebhookService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TelegramWebhookController(
                new OpenClawProperties.TelegramProperties(true, "bot-token", "expected-secret", "/api/telegram/webhook", "https://example.com/api/telegram/webhook"),
                telegramWebhookService
        )).build();

        mockMvc.perform(post("/api/telegram/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Telegram-Bot-Api-Secret-Token", "expected-secret")
                        .content("""
                                {
                                  "update_id": 1001,
                                  "message": {
                                    "message_id": 3001,
                                    "text": "你好",
                                    "chat": {
                                      "id": 2001,
                                      "type": "private"
                                    },
                                    "from": {
                                      "id": 4001
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        verify(telegramWebhookService).handle(any(TelegramUpdate.class));
    }
}
