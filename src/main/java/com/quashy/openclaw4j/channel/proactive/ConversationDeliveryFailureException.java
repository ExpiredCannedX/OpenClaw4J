package com.quashy.openclaw4j.channel.proactive;

/**
 * 表示主动回发流程中的可预期业务失败，并用稳定错误码把“缺失绑定”“不支持渠道”“渠道发送失败”等语义传递给调度器。
 */
public class ConversationDeliveryFailureException extends RuntimeException {

    /**
     * 标识当前失败的结构化错误码，供 reminder 调度器回写状态并决定是否重试。
     */
    private final String errorCode;

    /**
     * 通过显式错误码和消息构造异常，确保失败语义不会退化成难以分类的裸运行时异常。
     */
    public ConversationDeliveryFailureException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 允许保留底层异常作为原因链，便于调试 Telegram 等渠道发送失败的根因。
     */
    public ConversationDeliveryFailureException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 返回当前失败对应的稳定错误码，供上层状态机和观测系统统一消费。
     */
    public String errorCode() {
        return errorCode;
    }
}