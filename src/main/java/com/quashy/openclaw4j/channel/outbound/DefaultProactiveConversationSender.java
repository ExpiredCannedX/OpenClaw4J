package com.quashy.openclaw4j.channel.outbound;

import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.repository.ConversationDeliveryTargetRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认主动回发网关，负责先按内部会话解析回发目标，再根据渠道标识路由到具体 sender。
 */
@Service
public class DefaultProactiveConversationSender implements ProactiveConversationSender {

    /**
     * 提供内部会话到渠道目标的解析能力，使异步系统无需持有任何渠道原生目标即可请求回发。
     */
    private final ConversationDeliveryTargetRepository conversationDeliveryTargetRepository;

    /**
     * 按渠道标识索引具体 sender，实现运行时路由而不把 Scheduler 与 Telegram 等实现耦合在一起。
     */
    private final Map<String, ChannelProactiveConversationSender> sendersByChannel;

    /**
     * 在启动阶段完成 sender 索引构建和重复渠道校验，把歧义问题前置到装配阶段暴露。
     */
    public DefaultProactiveConversationSender(
            ConversationDeliveryTargetRepository conversationDeliveryTargetRepository,
            List<ChannelProactiveConversationSender> senders
    ) {
        this.conversationDeliveryTargetRepository = conversationDeliveryTargetRepository;
        LinkedHashMap<String, ChannelProactiveConversationSender> indexedSenders = new LinkedHashMap<>();
        for (ChannelProactiveConversationSender sender : senders) {
            ChannelProactiveConversationSender previous = indexedSenders.putIfAbsent(sender.channel(), sender);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate proactive sender channel detected: " + sender.channel());
            }
        }
        this.sendersByChannel = Map.copyOf(indexedSenders);
    }

    /**
     * 解析内部会话绑定并委托给对应渠道 sender；若绑定缺失或渠道未注册，直接抛出稳定业务异常。
     */
    @Override
    public void sendText(InternalConversationId conversationId, String text) {
        ConversationDeliveryTarget target = conversationDeliveryTargetRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new ConversationDeliveryFailureException(
                        "delivery_target_missing",
                        "未找到内部会话对应的回发目标绑定。"
                ));
        ChannelProactiveConversationSender sender = sendersByChannel.get(target.channel());
        if (sender == null) {
            throw new ConversationDeliveryFailureException(
                    "channel_not_supported",
                    "当前渠道尚未实现主动文本回发。"
            );
        }
        sender.sendText(target, text);
    }
}
