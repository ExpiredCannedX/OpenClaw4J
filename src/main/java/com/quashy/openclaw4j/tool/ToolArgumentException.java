package com.quashy.openclaw4j.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 表示工具在参数校验阶段发现的可预期错误，使执行器能够把业务参数问题映射成稳定错误码。
 */
public class ToolArgumentException extends RuntimeException {

    /**
     * 承载工具愿意暴露给上层的结构化参数错误细节，例如缺失字段或取值约束。
     */
    private final Map<String, Object> details;

    /**
     * 使用错误消息和细节构造参数异常，便于执行器统一转换为 `invalid_arguments` 结果。
     */
    public ToolArgumentException(String message, Map<String, Object> details) {
        super(message);
        this.details = Map.copyOf(Objects.requireNonNullElse(details, Map.of()));
    }

    /**
     * 暴露结构化参数错误细节，供执行器写入统一错误结果。
     */
    public Map<String, Object> details() {
        return details;
    }
}
