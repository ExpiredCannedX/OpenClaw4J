package com.quashy.openclaw4j.tool.api;

import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;

import java.util.Map;

/**
 * 定义本地同步工具的最小执行边界，使 Agent Core 可以通过统一抽象发现并调用内置工具。
 */
public interface Tool {

    /**
     * 返回暴露给模型和注册中心的标准化工具定义，确保目录视图不依赖具体实现细节。
     */
    ToolDefinition definition();

    /**
     * 返回只供服务端策略层消费的安全画像，默认按只读工具处理以兼容既有低风险工具实现。
     */
    default ToolSafetyProfile safetyProfile() {
        return ToolSafetyProfile.readOnly();
    }

    /**
     * 按统一请求模型同步执行工具，并只返回归一化后的业务载荷，异常由执行器统一收敛。
     */
    Map<String, Object> execute(ToolCallRequest request);
}
