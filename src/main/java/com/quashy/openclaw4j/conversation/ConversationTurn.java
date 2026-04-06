package com.quashy.openclaw4j.conversation;

/**
 * 表示内部会话中的一条标准化轮次记录，供 recent turns 读取和上下文拼装复用。
 */
public record ConversationTurn(
        /**
         * 记录该轮消息的说话方，用于上下文组装时恢复用户与助手的发言边界。
         */
        ConversationRole role,
        /**
         * 记录该轮的纯文本内容，是 recent turns 注入提示词时的核心载荷。
         */
        String content
) {

    /**
     * 用用户角色创建轮次，统一 recent turns 的写入格式，避免调用方重复处理角色细节。
     */
    public static ConversationTurn user(String content) {
        return new ConversationTurn(ConversationRole.USER, content);
    }

    /**
     * 用助手角色创建轮次，保证模型回复落盘时的角色标记与读取侧约定一致。
     */
    public static ConversationTurn assistant(String content) {
        return new ConversationTurn(ConversationRole.ASSISTANT, content);
    }
}