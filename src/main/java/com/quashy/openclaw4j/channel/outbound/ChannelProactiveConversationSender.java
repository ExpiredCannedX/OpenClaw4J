package com.quashy.openclaw4j.channel.outbound;

import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;

/**
 * 定义按渠道发送主动文本的最小扩展点，使平台无关的回发网关可以通过 channel 名路由到具体实现。
 */
public interface ChannelProactiveConversationSender {

    /**
     * 返回当前 sender 负责的渠道标识，供统一网关在解析绑定后选择正确实现。
     */
    String channel();

    /**
     * 按当前渠道目标发送一条最终文本；实现必须把渠道失败转换成稳定的错误语义，而不是静默吞掉。
     */
    void sendText(ConversationDeliveryTarget target, String text);
}
