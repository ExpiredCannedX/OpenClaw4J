package com.quashy.openclaw4j.config;

import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
         * 收敛 MCP server 的声明式配置，确保启动策略、命令行参数与超时预算由集中配置统一驱动。
         */
        McpProperties mcp,
        /**
         * 收敛运行期可观测性模式与 sink 相关配置，确保默认行为能在应用级统一声明。
         */
        ObservabilityProperties observability,
        /**
         * 收敛 reminder V1 的本地 SQLite 文件路径，避免异步任务事实源散落在具体仓储实现中。
         */
        ReminderProperties reminder,
        /**
         * 收敛 Scheduler heartbeat、批量扫描与失败重试预算，确保后台调度行为由集中配置统一驱动。
         */
        SchedulerProperties scheduler,
        /**
         * 收敛 memory V1 的本地索引文件配置，避免 SQLite 路径散落在索引器与工具实现中。
         */
        MemoryProperties memory,
        /**
         * 收敛工具执行前安全治理所需的本地数据库、确认短语、过期窗口和敏感文件 denylist 配置。
         */
        ToolSafetyProperties toolSafety
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
        mcp = mcp != null ? mcp : new McpProperties(null, null);
        observability = observability != null ? observability : new ObservabilityProperties(RuntimeObservationMode.TIMELINE, true, 160);
        reminder = reminder != null ? reminder : new ReminderProperties(null);
        scheduler = scheduler != null ? scheduler : new SchedulerProperties(null, 0, 0, null);
        memory = memory != null ? memory : new MemoryProperties(null);
        toolSafety = toolSafety != null ? toolSafety : new ToolSafetyProperties(null, null, null, null);
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
     * 描述 MCP client 需要的集中配置，使 server 生命周期管理、discovery 与调用超时都能由应用配置统一声明。
     */
    public record McpProperties(
            /**
             * 控制单次 MCP 请求的默认超时预算，避免 discovery 或 invocation 无限阻塞主链路。
             */
            Duration requestTimeout,
            /**
             * 以 server alias 为键收敛所有 `stdio` MCP server 配置，保证命名与启动策略天然绑定。
             */
            Map<String, McpServerProperties> servers
    ) {

        /**
         * 为 MCP client 提供稳定默认值，保证未配置 server 时主链路仍保持“仅本地工具”的现有行为。
         */
        public McpProperties {
            requestTimeout = requestTimeout != null && !requestTimeout.isNegative() && !requestTimeout.isZero()
                    ? requestTimeout
                    : Duration.ofSeconds(20);
            servers = immutableServerMap(servers);
        }

        /**
         * 递归冻结 server 配置映射，同时保留声明顺序，便于后续按配置顺序启动与观测。
         */
        private static Map<String, McpServerProperties> immutableServerMap(Map<String, McpServerProperties> servers) {
            if (servers == null || servers.isEmpty()) {
                return Map.of();
            }
            Map<String, McpServerProperties> copiedServers = new LinkedHashMap<>();
            servers.forEach((alias, serverProperties) -> copiedServers.put(
                    alias,
                    serverProperties != null ? serverProperties : new McpServerProperties(null, null, null, null, false)
            ));
            return java.util.Collections.unmodifiableMap(copiedServers);
        }
    }

    /**
     * 描述单个 `stdio` MCP server 的启动契约，使 alias、命令、环境与降级策略拥有清晰边界。
     */
    public record McpServerProperties(
            /**
             * 控制启动该 MCP server 的命令；在 Windows 上通常为 `cmd.exe`，由 args 再传入真实批处理命令。
             */
            String command,
            /**
             * 承载 `stdio` server 启动参数，顺序必须保持稳定，避免命令行语义被破坏。
             */
            List<String> args,
            /**
             * 承载该 server 进程专属环境变量，避免敏感配置散落在实现类或全局环境中。
             */
            Map<String, String> env,
            /**
             * 控制 server 进程的工作目录，便于 filesystem 等本地型 MCP server 绑定指定 workspace。
             */
            String workingDirectory,
            /**
             * 标记该 server 是否属于强依赖；强依赖初始化失败时主应用必须 fail-fast。
             */
            boolean required
    ) {

        /**
         * 对单个 server 配置做最小兜底，避免后续生命周期管理层充满空集合和空字符串判断。
         */
        public McpServerProperties {
            command = StringUtils.hasText(command) ? command : "";
            args = args != null ? List.copyOf(args) : List.of();
            env = env != null ? Map.copyOf(env) : Map.of();
            workingDirectory = StringUtils.hasText(workingDirectory) ? workingDirectory : "";
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

    /**
     * 描述 reminder V1 的本地持久化文件落点，使提醒任务与 memory 索引拥有各自明确的数据职责边界。
     */
    public record ReminderProperties(
            /**
             * 指向 reminder SQLite 单文件事实源的相对或绝对路径；相对路径默认以 workspace 根目录为基准解析。
             */
            String databaseFile
    ) {

        /**
         * 为 reminder 事实源文件提供稳定默认值，保证首次启用提醒能力时能在 workspace 下自动建库。
         */
        public ReminderProperties {
            databaseFile = StringUtils.hasText(databaseFile) ? databaseFile : ".openclaw/reminders.sqlite";
        }
    }

    /**
     * 描述工具安全治理需要的最小集中配置，使待确认状态、审计、显式确认和敏感文件边界都能统一外置。
     */
    public record ToolSafetyProperties(
            /**
             * 指向工具安全治理 SQLite 单文件事实源的相对或绝对路径。
             */
            String databaseFile,
            /**
             * 控制待确认请求的失效窗口，超过后显式确认不再生效。
             */
            Duration confirmationTtl,
            /**
             * 收敛允许消费待确认项的显式确认短语集合。
             */
            List<String> confirmationPhrases,
            /**
             * 收敛 workspace 根目录下禁止高风险写操作触达的敏感相对路径集合。
             */
            List<String> sensitivePaths
    ) {

        /**
         * 为工具安全治理配置提供稳定默认值，保证本地开发和测试在未显式配置时仍具备最小安全边界。
         */
        public ToolSafetyProperties {
            databaseFile = StringUtils.hasText(databaseFile) ? databaseFile : ".openclaw/tool-safety.sqlite";
            confirmationTtl = confirmationTtl != null && !confirmationTtl.isNegative() && !confirmationTtl.isZero()
                    ? confirmationTtl
                    : Duration.ofMinutes(10);
            confirmationPhrases = confirmationPhrases == null || confirmationPhrases.isEmpty()
                    ? List.of("确认", "继续", "yes", "confirm")
                    : List.copyOf(confirmationPhrases);
            sensitivePaths = sensitivePaths == null || sensitivePaths.isEmpty()
                    ? List.of("AGENTS.md", "SOUL.md", "SKILLS.md")
                    : List.copyOf(sensitivePaths);
        }
    }

    /**
     * 描述 reminder Scheduler V1 的最小运行时参数，使 heartbeat、批量扫描和失败重试规则都能稳定外置。
     */
    public record SchedulerProperties(
            /**
             * 控制后台 heartbeat 的固定扫描间隔，避免调度频率被硬编码在实现类中。
             */
            Duration heartbeat,
            /**
             * 控制单次 heartbeat 最多 claim 的到期任务数，避免扫描阶段无限拉取导致单轮阻塞。
             */
            int scanBatchSize,
            /**
             * 控制 reminder 自动重试的最大失败次数，超过预算后任务进入终态失败。
             */
            int maxRetryAttempts,
            /**
             * 控制 reminder 失败后的固定退避时长，避免短暂故障时立即热循环重试。
             */
            Duration retryBackoff
    ) {

        /**
         * 为 Scheduler 提供稳定默认值，保证本地开发和测试在未显式声明时仍保持最小可运行语义。
         */
        public SchedulerProperties {
            heartbeat = heartbeat != null && !heartbeat.isNegative() && !heartbeat.isZero() ? heartbeat : Duration.ofSeconds(15);
            scanBatchSize = scanBatchSize > 0 ? scanBatchSize : 20;
            maxRetryAttempts = maxRetryAttempts >= 0 ? maxRetryAttempts : 3;
            retryBackoff = retryBackoff != null && !retryBackoff.isNegative() && !retryBackoff.isZero() ? retryBackoff : Duration.ofMinutes(3);
        }
    }
}
