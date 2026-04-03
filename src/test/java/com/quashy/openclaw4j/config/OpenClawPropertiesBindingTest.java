package com.quashy.openclaw4j.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 `application.yaml` 中声明的调试入口与 Telegram 配置能够完整绑定到集中配置对象，避免出现“配置已写但代码未读”的漂移。
 */
class OpenClawPropertiesBindingTest {

    /**
     * 使用与应用启动一致的 Spring Boot 配置绑定路径，确保运行时真的能从配置源读取调试入口和 Telegram 参数。
     */
    @Test
    void shouldBindDebugAndTelegramPropertiesFromConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "openclaw.workspace-root=./workspace",
                        "openclaw.recent-turn-limit=6",
                        "openclaw.fallback-reply=fallback",
                        "openclaw.debug.default-prompt=来自配置的默认问题",
                        "openclaw.telegram.enabled=true",
                        "openclaw.telegram.bot-token=telegram-bot-token",
                        "openclaw.telegram.webhook-secret=telegram-secret-token",
                        "openclaw.telegram.webhook-path=/api/telegram/webhook",
                        "openclaw.telegram.webhook-url=https://example.ngrok-free.app/api/telegram/webhook"
                )
                .run(context -> {
                    OpenClawProperties properties = context.getBean(OpenClawProperties.class);

                    assertThat(properties.debug().defaultPrompt()).isEqualTo("来自配置的默认问题");
                    assertThat(properties.telegram().enabled()).isTrue();
                    assertThat(properties.telegram().botToken()).isEqualTo("telegram-bot-token");
                    assertThat(properties.telegram().webhookSecret()).isEqualTo("telegram-secret-token");
                    assertThat(properties.telegram().webhookPath()).isEqualTo("/api/telegram/webhook");
                    assertThat(properties.telegram().webhookUrl()).isEqualTo("https://example.ngrok-free.app/api/telegram/webhook");
                });
    }

    /**
     * 只为测试打开 `OpenClawProperties` 的配置绑定，避免引入与本用例无关的应用上下文依赖。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenClawProperties.class)
    static class TestConfiguration {
    }
}
