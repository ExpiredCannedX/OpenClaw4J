package com.quashy.openclaw4j.tool.safety.policy;

import com.quashy.openclaw4j.tool.api.Tool;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionError;
import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import com.quashy.openclaw4j.tool.model.ToolExecutionSuccess;
import com.quashy.openclaw4j.tool.safety.audit.ToolAuditLogEntry;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationService;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolPendingConfirmationRecord;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;
import com.quashy.openclaw4j.tool.safety.port.ToolAuditLogRepository;
import com.quashy.openclaw4j.tool.safety.validator.FilesystemWriteArgumentValidator;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在任何真实工具执行前统一实施服务端安全策略，收敛风险判定、确认态创建和参数级安全校验。
 */
public class ToolPolicyGuard {

    /**
     * 负责待确认状态流转与显式确认恢复校验，使策略层不需要直接处理持久化细节。
     */
    private final ToolConfirmationService confirmationService;

    /**
     * 负责追加结构化审计事件，确保策略判定路径可追踪。
     */
    private final ToolAuditLogRepository auditLogRepository;

    /**
     * 负责 filesystem 写请求的参数级安全校验，阻断路径逃逸与敏感文件修改。
     */
    private final FilesystemWriteArgumentValidator filesystemWriteArgumentValidator;

    /**
     * 通过显式依赖注入固定策略层所需的确认、审计和参数校验能力。
     */
    public ToolPolicyGuard(
            ToolConfirmationService confirmationService,
            ToolAuditLogRepository auditLogRepository,
            FilesystemWriteArgumentValidator filesystemWriteArgumentValidator
    ) {
        this.confirmationService = confirmationService;
        this.auditLogRepository = auditLogRepository;
        this.filesystemWriteArgumentValidator = filesystemWriteArgumentValidator;
    }

    /**
     * 基于工具安全画像、请求参数和确认态对单次工具调用作出最终放行结论。
     */
    public ToolPolicyDecision evaluate(Tool tool, ToolCallRequest request) {
        Assert.notNull(tool, "tool must not be null");
        Assert.notNull(request, "request must not be null");
        ToolSafetyProfile safetyProfile = tool.safetyProfile();
        ToolPolicyDecision validationDecision = validateArguments(safetyProfile, request);
        if (validationDecision.verdict() == ToolPolicyVerdict.DENIED) {
            appendAudit(request, validationDecision, null);
            return validationDecision;
        }
        if (safetyProfile.confirmationPolicy() == ToolConfirmationPolicy.EXPLICIT) {
            if (confirmationService.isExecutionConfirmed(request)) {
                ToolPolicyDecision allowedDecision = ToolPolicyDecision.allowed(Map.of(
                        "riskLevel", safetyProfile.riskLevel().name(),
                        "confirmationPolicy", safetyProfile.confirmationPolicy().name()
                ));
                appendAudit(request, allowedDecision, null);
                return allowedDecision;
            }
            ToolPendingConfirmationRecord pendingRecord = confirmationService.createPendingConfirmation(
                    request,
                    safetyProfile,
                    "tool_confirmation_required"
            );
            ToolPolicyDecision confirmationDecision = ToolPolicyDecision.confirmationRequired(
                    "confirmation_required",
                    "Tool request requires explicit confirmation before execution.",
                    Map.of(
                            "riskLevel", safetyProfile.riskLevel().name(),
                            "confirmationPolicy", safetyProfile.confirmationPolicy().name(),
                            "riskSummary", pendingRecord.riskSummary(),
                            "expiresAt", pendingRecord.expiresAt().toString()
                    ),
                    pendingRecord.confirmationId()
            );
            appendAudit(request, confirmationDecision, pendingRecord);
            return confirmationDecision;
        }
        ToolPolicyDecision allowedDecision = ToolPolicyDecision.allowed(Map.of(
                "riskLevel", safetyProfile.riskLevel().name(),
                "confirmationPolicy", safetyProfile.confirmationPolicy().name()
        ));
        appendAudit(request, allowedDecision, null);
        return allowedDecision;
    }

    /**
     * 根据画像选择参数校验器，并在发现危险参数时直接返回拒绝结论。
     */
    private ToolPolicyDecision validateArguments(ToolSafetyProfile safetyProfile, ToolCallRequest request) {
        if (safetyProfile.validatorType() != ToolArgumentValidatorType.FILESYSTEM_WRITE) {
            return ToolPolicyDecision.allowed(Map.of("validatorType", safetyProfile.validatorType().name()));
        }
        return filesystemWriteArgumentValidator.validate(request);
    }

    /**
     * 为策略判定追加结构化审计事件，避免拒绝和待确认结果只停留在内存里。
     */
    private void appendAudit(
            ToolCallRequest request,
            ToolPolicyDecision decision,
            ToolPendingConfirmationRecord pendingRecord
    ) {
        if (request.executionContext() == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>(decision.details());
        details.put("toolName", request.toolName());
        auditLogRepository.appendAuditLog(new ToolAuditLogEntry(
                "policy_decision",
                pendingRecord == null ? decision.confirmationId() : pendingRecord.confirmationId(),
                request.executionContext().conversationId(),
                request.executionContext().userId(),
                request.toolName(),
                request.toolName() + ":" + request.arguments().hashCode(),
                decision.verdict().name(),
                pendingRecord == null ? null : pendingRecord.status().name(),
                null,
                decision.reasonCode(),
                Map.copyOf(details),
                Instant.now()
        ));
    }

    /**
     * 在执行器拿到最终结构化结果后记录补充审计信息，并在确认恢复执行完成时消费待确认记录。
     */
    public void recordOutcome(ToolCallRequest request, ToolExecutionResult result) {
        if (request.executionContext() == null) {
            return;
        }
        String executionOutcome = result instanceof ToolExecutionSuccess
                ? "success"
                : ((ToolExecutionError) result).errorCode();
        Map<String, Object> details = result instanceof ToolExecutionSuccess success
                ? Map.of("toolName", success.toolName())
                : Map.of("message", ((ToolExecutionError) result).message());
        if (request.executionContext().confirmedPendingRequestId() != null) {
            confirmationService.markConsumed(
                    request,
                    executionOutcome,
                    "execution_completed",
                    details
            );
            return;
        }
        auditLogRepository.appendAuditLog(new ToolAuditLogEntry(
                "execution_outcome",
                null,
                request.executionContext().conversationId(),
                request.executionContext().userId(),
                request.toolName(),
                request.toolName() + ":" + request.arguments().hashCode(),
                null,
                null,
                executionOutcome,
                "execution_completed",
                details,
                Instant.now()
        ));
    }
}

