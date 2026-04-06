package com.quashy.openclaw4j.conversation.infrastructure.memory;

import com.quashy.openclaw4j.conversation.ConversationTurn;
import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.port.ConversationTurnRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供首版内存 recent turns 仓储，让 Agent Core 可以在不引入数据库的前提下完成多轮上下文拼装。
 */
@Repository
public class InMemoryConversationTurnRepository implements ConversationTurnRepository {

    /**
     * 按内部会话缓存历史轮次，当前仅服务于 recent turns 读取，不承担长期持久化职责。
     */
    private final Map<String, List<ConversationTurn>> turnsByConversation = new ConcurrentHashMap<>();

    /**
     * 按写入顺序返回会话尾部的最近轮次，保证上下文组装拿到的历史与用户实际对话顺序一致。
     */
    @Override
    public List<ConversationTurn> loadRecentTurns(InternalConversationId conversationId, int limit) {
        List<ConversationTurn> storedTurns = turnsByConversation.getOrDefault(conversationId.value(), List.of());
        int fromIndex = Math.max(storedTurns.size() - limit, 0);
        return List.copyOf(storedTurns.subList(fromIndex, storedTurns.size()));
    }

    /**
     * 以追加方式写入轮次，保持实现简单可预测，并为后续替换为持久化存储保留同样的调用语义。
     */
    @Override
    public void appendTurn(InternalConversationId conversationId, ConversationTurn turn) {
        turnsByConversation.computeIfAbsent(conversationId.value(), ignored -> new ArrayList<>()).add(turn);
    }
}