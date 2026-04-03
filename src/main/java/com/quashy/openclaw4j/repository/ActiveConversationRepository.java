package com.quashy.openclaw4j.repository;

import com.quashy.openclaw4j.domain.InternalConversationId;

/**
 * 负责维护单聊场景下“同一渠道同一用户唯一活跃会话”的最小策略。
 */
public interface ActiveConversationRepository {

    /**
     * 为给定渠道用户返回当前活跃会话；若不存在则按当前策略创建并绑定一个新的内部会话。
     */
    InternalConversationId getOrCreateActiveConversation(String channel, String externalUserId);
}
