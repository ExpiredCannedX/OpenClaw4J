package com.quashy.openclaw4j.repository;

import com.quashy.openclaw4j.domain.ReplyEnvelope;

import java.util.Optional;

/**
 * 负责记录已处理过的外部消息，确保渠道重试或重复投递不会触发重复模型调用。
 */
public interface ProcessedMessageRepository {

    /**
     * 按渠道和外部消息 ID 查找已经计算出的回复，用于重复投递时直接返回稳定结果。
     */
    Optional<ReplyEnvelope> findProcessedReply(String channel, String externalMessageId);

    /**
     * 记录一次消息处理结果，使后续同一消息的再次投递能够幂等返回。
     */
    void saveProcessedReply(String channel, String externalMessageId, ReplyEnvelope replyEnvelope);
}
