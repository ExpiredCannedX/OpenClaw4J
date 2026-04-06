package com.quashy.openclaw4j.conversation.port;

import com.quashy.openclaw4j.conversation.ConversationTurn;
import com.quashy.openclaw4j.conversation.InternalConversationId;

import java.util.List;

/**
 * 负责保存和读取当前活跃会话的最近轮次，为上下文组装提供稳定来源。
 */
public interface ConversationTurnRepository {

    /**
     * 读取会话最近若干轮历史，并按原始顺序返回，避免调用方重复处理截断和排序逻辑。
     */
    List<ConversationTurn> loadRecentTurns(InternalConversationId conversationId, int limit);

    /**
     * 追加一条标准化轮次到指定会话，确保 Agent 输入输出都能沉淀为后续多轮上下文。
     */
    void appendTurn(InternalConversationId conversationId, ConversationTurn turn);
}