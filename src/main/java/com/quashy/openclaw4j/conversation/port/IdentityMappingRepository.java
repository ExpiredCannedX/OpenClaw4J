package com.quashy.openclaw4j.conversation.port;

import com.quashy.openclaw4j.conversation.InternalUserId;

/**
 * 负责维护外部渠道用户与内部用户身份之间的映射边界，保证核心域模型不依赖渠道原生 ID。
 */
public interface IdentityMappingRepository {

    /**
     * 为给定渠道用户返回稳定的内部用户 ID；若映射不存在则立即创建，避免上层服务自己拼装主键策略。
     */
    InternalUserId getOrCreateInternalUserId(String channel, String externalUserId);
}