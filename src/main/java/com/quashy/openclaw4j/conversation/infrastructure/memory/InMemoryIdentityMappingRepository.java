package com.quashy.openclaw4j.conversation.infrastructure.memory;

import com.quashy.openclaw4j.conversation.InternalUserId;
import com.quashy.openclaw4j.conversation.port.IdentityMappingRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供首版内存身份映射实现，用最小状态支持当前开发入口验证核心主链路。
 */
@Repository
public class InMemoryIdentityMappingRepository implements IdentityMappingRepository {

    /**
     * 按渠道和外部用户缓存内部用户映射，保证同一用户在单进程内获得稳定内部 ID。
     */
    private final Map<String, InternalUserId> mappings = new ConcurrentHashMap<>();

    /**
     * 在单进程内为同一渠道用户复用稳定的内部用户 ID，后续切换持久化实现时不影响上层调用约定。
     */
    @Override
    public InternalUserId getOrCreateInternalUserId(String channel, String externalUserId) {
        return mappings.computeIfAbsent(channel + "::" + externalUserId, ignored -> new InternalUserId(UUID.randomUUID().toString()));
    }
}