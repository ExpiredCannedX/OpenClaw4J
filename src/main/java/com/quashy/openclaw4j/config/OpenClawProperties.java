package com.quashy.openclaw4j.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 统一收敛单聊核心链路当前阶段需要的可配置项，避免 workspace、上下文轮次和兜底文案散落在实现细节中。
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
        String fallbackReply
) {

    /**
     * 对缺省配置做最小兜底，保证本地开发和测试在未显式声明配置时仍能得到稳定行为。
     */
    public OpenClawProperties {
        workspaceRoot = StringUtils.hasText(workspaceRoot) ? workspaceRoot : "workspace";
        recentTurnLimit = recentTurnLimit > 0 ? recentTurnLimit : 6;
        fallbackReply = StringUtils.hasText(fallbackReply) ? fallbackReply : "系统暂时繁忙，请稍后再试。";
    }
}
