package com.quashy.openclaw4j.tool.runtime;

import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 维护当前进程内注册的工具目录，并在启动期阻止重复工具名进入主链路。
 */
@Component
public class LocalToolRegistry implements ToolRegistry {

    /**
     * 以工具唯一名索引具体实现，保证执行阶段可以稳定按名解析目标工具。
     */
    private final Map<String, Tool> toolsByName;

    /**
     * 预先冻结标准化定义列表，避免每次组装 prompt 时重复从实现对象提取目录信息。
     */
    private final List<ToolDefinition> definitions;

    /**
     * 在注册阶段完成唯一名校验与目录快照构建，把歧义问题前置到应用启动或测试装配阶段。
     */
    public LocalToolRegistry(List<Tool> tools) {
        Assert.notNull(tools, "tools must not be null");
        LinkedHashMap<String, Tool> indexedTools = new LinkedHashMap<>();
        ArrayList<ToolDefinition> collectedDefinitions = new ArrayList<>();
        for (Tool tool : tools) {
            ToolDefinition definition = tool.definition();
            Tool previous = indexedTools.putIfAbsent(definition.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool name detected: " + definition.name());
            }
            collectedDefinitions.add(definition);
        }
        this.toolsByName = Map.copyOf(indexedTools);
        this.definitions = List.copyOf(collectedDefinitions);
    }

    /**
     * 返回已经通过唯一名校验的目录快照，供规划阶段直接暴露给模型。
     */
    @Override
    public List<ToolDefinition> listDefinitions() {
        return definitions;
    }

    /**
     * 按唯一名解析本地工具实现，未命中时返回空结果交由执行器决定降级策略。
     */
    @Override
    public Optional<Tool> findByName(String toolName) {
        return Optional.ofNullable(toolsByName.get(toolName));
    }
}
