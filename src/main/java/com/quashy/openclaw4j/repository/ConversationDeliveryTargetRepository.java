package com.quashy.openclaw4j.repository;

import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;
import com.quashy.openclaw4j.domain.InternalConversationId;

import java.util.Optional;

/**
 * 维护内部会话到渠道回发目标的绑定，使异步子系统能仅凭内部会话标识解析出正确的出站目标。
 */
public interface ConversationDeliveryTargetRepository {

    /**
     * 以内部会话为主键写入或刷新当前可用的回发目标，保证后续异步履约总能读取到最新绑定。
     */
    void save(ConversationDeliveryTarget target);

    /**
     * 按内部会话标识解析当前回发目标，未命中时返回空结果交给上层决定重试或失败语义。
     */
    Optional<ConversationDeliveryTarget> findByConversationId(InternalConversationId conversationId);
}
