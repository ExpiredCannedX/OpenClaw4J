package com.quashy.openclaw4j.store.memory;

import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.repository.ActiveConversationRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供首版内存活跃会话实现，直接体现当前 change 的单聊会话复用策略。
 */
@Repository
public class InMemoryActiveConversationRepository implements ActiveConversationRepository {

    /**
     * 按渠道与外部用户缓存当前活跃会话，直接体现单进程内的单聊会话复用策略。
     */
    private final Map<String, InternalConversationId> conversations = new ConcurrentHashMap<>();

    /**
     * 对同一渠道用户始终返回同一个活跃会话 ID，确保多轮上下文在单进程内连续。
     */
    @Override
    public InternalConversationId getOrCreateActiveConversation(String channel, String externalUserId) {
        return conversations.computeIfAbsent(channel + "::" + externalUserId, ignored -> new InternalConversationId(UUID.randomUUID().toString()));
    }
}
