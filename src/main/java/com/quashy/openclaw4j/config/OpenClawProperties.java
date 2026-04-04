package com.quashy.openclaw4j.config;

import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 统一收敛单聊核心链路当前阶段需要的可配置项，避免 workspace、上下文轮次、调试入口文案和 Telegram 参数散落在实现细节中。
 */
@ConfigurationProperties(prefix = "openclaw")
public record OpenClawProperties(
        /**
         * 指向当前 Agent workspace 根目录的路径，workspace 加载器会从这里读取核心上下文文件。
         */
        String workspaceRoot,
        /**
         * 控制 recent turns 最大读取数量，避免上下文无限膨胀。
         */
        int recentTurnLimit,
        /**
         * 当模型调用失败时直接返回给用户的安全兜底文案。
         */
        String fallbackReply,
        /**
         * 收敛旧调试入口相关的运行时配置，避免控制器继续持有固定默认 prompt。
         */
        DebugProperties debug,
        /**
         * 收敛 Telegram 渠道所需的运行时配置，确保 `application.yaml` 中的 Telegram 字段能够被正式绑定和消费。
         */
        TelegramProperties telegram,
        /**
         * 收敛运行期可观测性模式与 sink 相关配置，确保默认行为能在应用级统一声明。
         */
        ObservabilityProperties observability,
        /**
         * 收敛 memory V1 的本地索引文件配置，避免 SQLite 路径散落在索引器与工具实现中。
         */
        MemoryProperties memory
) {

    /**
     * 对缺省配置做最小兜底，保证本地开发和测试在未显式声明配置时仍能得到稳定行为。
     */
    public OpenClawProperties {
        workspaceRoot = StringUtils.hasText(workspaceRoot) ? workspaceRoot : "workspace";
        recentTurnLimit = recentTurnLimit > 0 ? recentTurnLimit : 6;
        fallbackReply = StringUtils.hasText(fallbackReply) ? fallbackReply : "系统暂时繁忙，请稍后再试。";
        debug = debug != null ? debug : new DebugProperties(null);
        telegram = telegram != null ? telegram : new TelegramProperties(false, null, null, null, null);
        observability = observability != null ? observability : new ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160);
        memory = memory != null ? memory : new MemoryProperties(null);
    }

    /**
     * 收敛旧调试入口配置，确保默认 prompt 能通过应用配置覆盖，而不是固化在控制器实现中。
     */
    public record DebugProperties(
            /**
             * 调试入口在调用方未传 prompt 时回退使用的默认提问文案。
             */
            String defaultPrompt
    ) {

        /**
         * 为调试入口默认 prompt 提供稳定兜底，保证本地开发在未显式配置时仍能保留旧 PoC 的可用性。
         */
        public DebugProperties {
            defaultPrompt = StringUtils.hasText(defaultPrompt) ? defaultPrompt : "你好，介绍下你自己！";
        }
    }

    /**
     * 描述 Telegram 渠道接入所需的最小运行时参数，便于 adapter 落地时直接复用集中配置。
     */
    public record TelegramProperties(
            /**
             * 控制 Telegram adapter 是否启用，避免配置未齐时误暴露半成品入口。
             */
            boolean enabled,
            /**
             * BotFather 生成的 bot token，用于调用 Telegram Bot API。
             */
            String botToken,
            /**
             * 与 Telegram webhook 约定的共享密钥，用于校验回调来源。
             */
            String webhookSecret,
            /**
             * 应用内部暴露给 Telegram webhook 的请求路径。
             */
            String webhookPath,
            /**
             * Telegram 可访问的完整公网 HTTPS webhook 地址。
             */
            String webhookUrl
    ) {

        /**
         * 对 Telegram 配置提供最小安全兜底，保证缺省场景下不会因为空值传播而引入额外判空分支。
         */
        public TelegramProperties {
            botToken = StringUtils.hasText(botToken) ? botToken : "";
            webhookSecret = StringUtils.hasText(webhookSecret) ? webhookSecret : "";
            webhookPath = StringUtils.hasText(webhookPath) ? webhookPath : "/api/telegram/webhook";
            webhookUrl = StringUtils.hasText(webhookUrl) ? webhookUrl : "";
        }
    }

    /**
     * 描述运行期可观测性的默认模式和 sink 级配置，避免这些横切参数散落在业务类中。
     */
    public record ObservabilityProperties(
            /**
             * 控制当前进程启用的观测模式，是事件过滤与字段分级的唯一入口。
             */
            RuntimeObservationMode mode,
            /**
             * 控制首个控制台 sink 是否启用，便于开发者在不改代码的情况下快速关闭输出。
             */
            boolean consoleEnabled,
            /**
             * 控制 `VERBOSE` 模式下详细预览字段的最大长度，避免原始 prompt 或回复被完整打印。
             */
            int verbosePreviewLength
    ) {

        /**
         * 对运行期可观测性配置提供稳定默认值，保证应用在未显式声明时仍按时间线模式工作。
         */
        public ObservabilityProperties {
            mode = mode != null ? mode : RuntimeObservationMode.TIMELINE;
            verbosePreviewLength = verbosePreviewLength > 0 ? verbosePreviewLength : 160;
        }
    }

    /**
     * 描述 memory V1 需要的最小索引配置，使本地 SQLite 单文件索引拥有稳定默认落点。
     */
    public record MemoryProperties(
            /**
             * 指向 memory SQLite 单文件索引的相对或绝对路径；相对路径默认以 workspace 根目录为基准解析。
             */
            String indexFile
    ) {

        /**
         * 为 memory 索引文件提供稳定默认值，保证首次启用 memory 时能在 workspace 下自动建库。
         */
        public MemoryProperties {
            indexFile = StringUtils.hasText(indexFile) ? indexFile : ".openclaw/memory-index.sqlite";
        }
    }
}
