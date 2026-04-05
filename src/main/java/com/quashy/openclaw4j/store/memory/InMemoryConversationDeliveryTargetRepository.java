package com.quashy.openclaw4j.store.memory;

import com.quashy.openclaw4j.domain.ConversationDeliveryTarget;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.repository.ConversationDeliveryTargetRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供测试和轻量场景可用的内存版会话回发目标仓储，避免单元测试为了绑定行为引入真实 SQLite 依赖。
 */
public class InMemoryConversationDeliveryTargetRepository implements ConversationDeliveryTargetRepository {

    /**
     * 按内部会话值缓存最近一次刷新后的渠道目标，使测试能直接观察绑定覆盖语义。
     */
    private final Map<String, ConversationDeliveryTarget> targetsByConversationId = new ConcurrentHashMap<>();

    /**
     * 以内部会话为键写入或覆盖目标绑定，模拟生产仓储的 upsert 语义。
     */
    @Override
    public void save(ConversationDeliveryTarget target) {
        targetsByConversationId.put(target.conversationId().value(), target);
    }

    /**
     * 返回当前内部会话最近一次保存的目标绑定，便于测试异步回发前的解析行为。
     */
    @Override
    public Optional<ConversationDeliveryTarget> findByConversationId(InternalConversationId conversationId) {
        return Optional.ofNullable(targetsByConversationId.get(conversationId.value()));
    }
}
