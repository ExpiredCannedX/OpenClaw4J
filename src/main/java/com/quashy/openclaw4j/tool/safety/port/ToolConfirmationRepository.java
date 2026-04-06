package com.quashy.openclaw4j.tool.safety.port;

import com.quashy.openclaw4j.conversation.InternalConversationId;
import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolPendingConfirmationRecord;

import java.time.Instant;
import java.util.Optional;

/**
 * 抽象待确认请求的持久化边界，使确认流服务不依赖具体 SQLite 访问细节。
 */
public interface ToolConfirmationRepository {

    /**
     * 返回当前会话和用户下仍然有效的活跃待确认项，供策略层限制一次只存在一个待确认请求。
     */
    Optional<ToolPendingConfirmationRecord> findActiveConfirmation(
            InternalConversationId conversationId,
            InternalUserId userId,
            Instant now
    );

    /**
     * 返回当前会话和用户最近一次确认记录，供显式确认消息判断过期或状态冲突。
     */
    Optional<ToolPendingConfirmationRecord> findLatestConfirmation(
            InternalConversationId conversationId,
            InternalUserId userId
    );

    /**
     * 按待确认记录标识查询完整记录，供恢复执行和消费后状态更新使用。
     */
    Optional<ToolPendingConfirmationRecord> findConfirmationById(String confirmationId);

    /**
     * 插入或更新一条完整确认记录，保持服务层状态机转换显式且可测试。
     */
    void upsertConfirmation(ToolPendingConfirmationRecord record);
}

