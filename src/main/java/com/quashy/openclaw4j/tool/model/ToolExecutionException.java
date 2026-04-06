package com.quashy.openclaw4j.tool.model;

import org.springframework.util.Assert;

import java.util.Map;
import java.util.Objects;

/**
 * 表示工具实现主动抛出的结构化执行异常，使执行器可以保留稳定错误码而不是再次折叠成泛化失败。
 */
public class ToolExecutionException extends RuntimeException {

    /**
     * 承载调用方可依赖的稳定错误码，用于区分超时、transport 失败和远端不可用等场景。
     */
    private final String errorCode;

    /**
     * 承载补充诊断字段，帮助最终回复阶段和观测链路理解错误上下文。
     */
    private final Map<String, Object> details;

    /**
     * 通过显式错误码、消息和细节构造结构化异常，避免工具执行层把关键失败语义丢给通用异常类型。
     */
    public ToolExecutionException(String errorCode, String message, Map<String, Object> details) {
        super(message);
        Assert.hasText(errorCode, "errorCode must not be blank");
        Assert.hasText(message, "message must not be blank");
        this.errorCode = errorCode;
        this.details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }

    /**
     * 返回稳定错误码，供执行器直接映射为结构化 `ToolExecutionError`。
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 返回补充诊断字段，供最终回复阶段和观测链路按需消费。
     */
    public Map<String, Object> details() {
        return details;
    }
}
