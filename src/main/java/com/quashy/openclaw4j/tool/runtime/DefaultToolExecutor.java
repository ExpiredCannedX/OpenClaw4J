package com.quashy.openclaw4j.tool.runtime;

import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.api.ToolExecutor;
import com.quashy.openclaw4j.tool.api.ToolRegistry;
import com.quashy.openclaw4j.tool.model.*;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyDecision;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyGuard;
import com.quashy.openclaw4j.tool.safety.policy.ToolPolicyVerdict;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 负责把同步工具执行收敛为统一结果，避免主链路直接处理工具缺失、参数错误和运行异常。
 */
public class DefaultToolExecutor implements ToolExecutor {

    /**
     * 提供工具目录与按名解析能力，使执行器可以独立于具体装配方式运行。
     */
    private final ToolRegistry toolRegistry;

    /**
     * 在真实工具执行前实施统一安全判定；为空时沿用旧行为，便于兼容尚未接入安全治理的测试装配。
     */
    private final ToolPolicyGuard toolPolicyGuard;

    /**
     * 通过注册中心解析目标工具，把执行与目录发现职责解耦。
     */
    public DefaultToolExecutor(ToolRegistry toolRegistry) {
        this(toolRegistry, null);
    }

    /**
     * 通过注册中心和策略层共同约束工具调用边界，确保所有真实执行前都能先走统一安全判定。
     */
    public DefaultToolExecutor(ToolRegistry toolRegistry, ToolPolicyGuard toolPolicyGuard) {
        this.toolRegistry = toolRegistry;
        this.toolPolicyGuard = toolPolicyGuard;
    }

    /**
     * 统一执行工具并把所有失败路径转换成结构化错误结果，保证调用侧永远不接收裸异常。
     */
    @Override
    public ToolExecutionResult execute(ToolCallRequest request) {
        return toolRegistry.findByName(request.toolName())
                .<ToolExecutionResult>map(tool -> executeResolvedTool(tool, request))
                .orElseGet(() -> new ToolExecutionError(
                        request.toolName(),
                        "tool_not_found",
                        "Requested tool is not registered.",
                        Map.of()
                ));
    }

    /**
     * 在工具已成功解析后执行实际调用，并把参数错误与未知异常映射为稳定错误码。
     */
    private ToolExecutionResult executeResolvedTool(Tool tool, ToolCallRequest request) {
        ToolPolicyDecision policyDecision = evaluatePolicy(tool, request);
        if (policyDecision.verdict() == ToolPolicyVerdict.DENIED) {
            return new ToolExecutionError(
                    tool.definition().name(),
                    "policy_denied",
                    policyDecision.message(),
                    buildPolicyErrorDetails(policyDecision)
            );
        }
        if (policyDecision.verdict() == ToolPolicyVerdict.CONFIRMATION_REQUIRED) {
            return new ToolExecutionError(
                    tool.definition().name(),
                    "confirmation_required",
                    policyDecision.message(),
                    buildPolicyErrorDetails(policyDecision)
            );
        }
        try {
            ToolExecutionResult result = new ToolExecutionSuccess(tool.definition().name(), tool.execute(request));
            recordOutcome(request, result);
            return result;
        } catch (ToolArgumentException exception) {
            ToolExecutionResult result = new ToolExecutionError(
                    tool.definition().name(),
                    "invalid_arguments",
                    exception.getMessage(),
                    exception.details()
            );
            recordOutcome(request, result);
            return result;
        } catch (ToolExecutionException exception) {
            ToolExecutionResult result = new ToolExecutionError(
                    tool.definition().name(),
                    exception.errorCode(),
                    exception.getMessage(),
                    exception.details()
            );
            recordOutcome(request, result);
            return result;
        } catch (Exception exception) {
            ToolExecutionResult result = new ToolExecutionError(
                    tool.definition().name(),
                    "execution_failed",
                    exception.getMessage() == null ? "Tool execution failed." : exception.getMessage(),
                    Map.of("exceptionType", exception.getClass().getSimpleName())
            );
            recordOutcome(request, result);
            return result;
        }
    }

    /**
     * 在启用策略层时先做执行前安全判定；未接入策略层时回退为直接放行，兼容既有低风险测试装配。
     */
    private ToolPolicyDecision evaluatePolicy(Tool tool, ToolCallRequest request) {
        if (toolPolicyGuard == null) {
            return ToolPolicyDecision.allowed(Map.of());
        }
        return toolPolicyGuard.evaluate(tool, request);
    }

    /**
     * 把策略判定结果转换为统一错误细节，便于 Agent Core 和测试稳定断言拒绝或待确认语义。
     */
    private Map<String, Object> buildPolicyErrorDetails(ToolPolicyDecision decision) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>(decision.details());
        details.put("policyDecision", decision.verdict().name());
        details.put("reasonCode", decision.reasonCode());
        if (decision.confirmationId() != null) {
            details.put("confirmationId", decision.confirmationId());
        }
        return Map.copyOf(details);
    }

    /**
     * 在启用策略层时把最终执行结果补记到审计链路，并消费确认恢复执行对应的待确认记录。
     */
    private void recordOutcome(ToolCallRequest request, ToolExecutionResult result) {
        if (toolPolicyGuard == null) {
            return;
        }
        toolPolicyGuard.recordOutcome(request, result);
    }
}
