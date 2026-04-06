package com.quashy.openclaw4j.conversation.infrastructure.memory;

import com.quashy.openclaw4j.agent.model.ReplyEnvelope;
import com.quashy.openclaw4j.conversation.port.ProcessedMessageRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供首版内存消息去重实现，用最小代价保证开发入口面对重复投递时的幂等行为。
 */
@Repository
public class InMemoryProcessedMessageRepository implements ProcessedMessageRepository {

    /**
     * 缓存已经成功处理过的消息回复，用于重复投递时直接返回稳定结果。
     */
    private final Map<String, ReplyEnvelope> processedReplies = new ConcurrentHashMap<>();

    /**
     * 从内存缓存中读取之前已经计算好的回复，命中后即可跳过整条 Agent 主链路。
     */
    @Override
    public Optional<ReplyEnvelope> findProcessedReply(String channel, String externalMessageId) {
        return Optional.ofNullable(processedReplies.get(channel + "::" + externalMessageId));
    }

    /**
     * 将处理结果按渠道和消息 ID 记录下来，使后续重复消息能够获得稳定返回值。
     */
    @Override
    public void saveProcessedReply(String channel, String externalMessageId, ReplyEnvelope replyEnvelope) {
        processedReplies.put(channel + "::" + externalMessageId, replyEnvelope);
    }
}