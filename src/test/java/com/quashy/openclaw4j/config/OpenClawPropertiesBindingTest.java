package com.quashy.openclaw4j.config;

import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 `application.yaml` 中声明的调试入口、Telegram、MCP、运行期观测以及 reminder/scheduler 配置能够完整绑定到集中配置对象。
 */
class OpenClawPropertiesBindingTest {

    /**
     * 使用与应用启动一致的 Spring Boot 配置绑定路径，确保运行时真的能从配置源读取调试入口、Telegram、MCP、观测以及 reminder/scheduler 参数。
     */
    @Test
    void shouldBindDebugTelegramMcpObservabilityMemoryAndReminderPropertiesFromConfiguration() {
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
                        "openclaw.telegram.webhook-url=https://example.ngrok-free.app/api/telegram/webhook",
                        "openclaw.mcp.request-timeout=PT8S",
                        "openclaw.mcp.servers.filesystem.command=cmd.exe",
                        "openclaw.mcp.servers.filesystem.args[0]=/c",
                        "openclaw.mcp.servers.filesystem.args[1]=npx",
                        "openclaw.mcp.servers.filesystem.env.API_KEY=test-key",
                        "openclaw.mcp.servers.filesystem.working-directory=./workspace",
                        "openclaw.mcp.servers.filesystem.required=true",
                        "openclaw.observability.mode=VERBOSE",
                        "openclaw.observability.console-enabled=false",
                        "openclaw.observability.verbose-preview-length=64",
                        "openclaw.memory.index-file=.openclaw/memory-index.sqlite",
                        "openclaw.reminder.database-file=.openclaw/reminders.sqlite",
                        "openclaw.scheduler.heartbeat=PT15S",
                        "openclaw.scheduler.scan-batch-size=12",
                        "openclaw.scheduler.max-retry-attempts=5",
                        "openclaw.scheduler.retry-backoff=PT3M"
                )
                .run(context -> {
                    OpenClawProperties properties = context.getBean(OpenClawProperties.class);

                    assertThat(properties.debug().defaultPrompt()).isEqualTo("来自配置的默认问题");
                    assertThat(properties.telegram().enabled()).isTrue();
                    assertThat(properties.telegram().botToken()).isEqualTo("telegram-bot-token");
                    assertThat(properties.telegram().webhookSecret()).isEqualTo("telegram-secret-token");
                    assertThat(properties.telegram().webhookPath()).isEqualTo("/api/telegram/webhook");
                    assertThat(properties.telegram().webhookUrl()).isEqualTo("https://example.ngrok-free.app/api/telegram/webhook");
                    Object mcpProperties = invokeNoArg(properties, "mcp");
                    assertThat(invokeNoArg(mcpProperties, "requestTimeout")).hasToString("PT8S");
                    Object filesystemServer = getMapValue(invokeNoArg(mcpProperties, "servers"), "filesystem");
                    assertThat(invokeNoArg(filesystemServer, "command")).isEqualTo("cmd.exe");
                    assertThat(invokeNoArg(filesystemServer, "args")).isEqualTo(java.util.List.of("/c", "npx"));
                    assertThat(getMapValue(invokeNoArg(filesystemServer, "env"), "API_KEY")).isEqualTo("test-key");
                    assertThat(invokeNoArg(filesystemServer, "workingDirectory")).isEqualTo("./workspace");
                    assertThat(invokeNoArg(filesystemServer, "required")).isEqualTo(true);
                    assertThat(properties.observability().mode()).isEqualTo(RuntimeObservationMode.VERBOSE);
                    assertThat(properties.observability().consoleEnabled()).isFalse();
                    assertThat(properties.observability().verbosePreviewLength()).isEqualTo(64);
                    assertThat(properties.memory().indexFile()).isEqualTo(".openclaw/memory-index.sqlite");
                    assertThat(properties.reminder().databaseFile()).isEqualTo(".openclaw/reminders.sqlite");
                    assertThat(properties.scheduler().heartbeat()).hasToString("PT15S");
                    assertThat(properties.scheduler().scanBatchSize()).isEqualTo(12);
                    assertThat(properties.scheduler().maxRetryAttempts()).isEqualTo(5);
                    assertThat(properties.scheduler().retryBackoff()).hasToString("PT3M");
                });
    }

    /**
     * 通过反射读取尚未在当前基线中公开的集中配置访问器，保证测试可以先锁定目标契约再驱动实现。
     */
    private Object invokeNoArg(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法调用方法: " + methodName, exception);
        }
    }

    /**
     * 通过统一 helper 读取 map 绑定结果中的指定键，避免测试主体掺杂重复的未检查类型转换。
     */
    @SuppressWarnings("unchecked")
    private Object getMapValue(Object target, String key) {
        return ((java.util.Map<String, Object>) target).get(key);
    }

    /**
     * 只为测试打开 `OpenClawProperties` 的配置绑定，避免引入与本用例无关的应用上下文依赖。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OpenClawProperties.class)
    static class TestConfiguration {
    }
}
