package com.quashy.openclaw4j.conversation;

/**
 * 描述最近会话轮次中消息的说话方，当前仅保留用户与助手两种最小角色以支撑单聊主链路。
 */
public enum ConversationRole {
    USER,
    ASSISTANT
}