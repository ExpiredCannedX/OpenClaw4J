package com.quashy.openclaw4j.agent.prompt;

import com.quashy.openclaw4j.agent.runtime.AgentObservation;
import com.quashy.openclaw4j.agent.runtime.AgentOrchestrationState;
import com.quashy.openclaw4j.agent.runtime.AgentOrchestrationTerminalReason;
import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.skill.ResolvedSkill;
import com.quashy.openclaw4j.tool.model.ToolExecutionError;
import com.quashy.openclaw4j.tool.model.ToolExecutionResult;
import com.quashy.openclaw4j.tool.model.ToolExecutionSuccess;
import com.quashy.openclaw4j.tool.schema.ToolDefinition;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 负责把 workspace 内容、工具目录和工具观察结果以确定顺序组装为模型输入，保证上下文来源清晰且可测试。
 */
@Component
public class AgentPromptAssembler {

    /**
     * 组装规划阶段 prompt，在基础上下文之后追加可用工具目录和结构化决策输出约束。
     */
    public AgentPrompt assemblePlanningPrompt(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message,
            List<ToolDefinition> availableTools
    ) {
        return assemblePlanningPrompt(
                workspaceSnapshot,
                selectedSkill,
                recentTurns,
                message,
                availableTools,
                new AgentOrchestrationState(1)
        );
    }

    /**
     * 组装带有多步编排状态的规划阶段 prompt，使模型能看到剩余 budget 与按序观察历史后再决定下一步动作。
     */
    public AgentPrompt assemblePlanningPrompt(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message,
            List<ToolDefinition> availableTools,
            AgentOrchestrationState orchestrationState
    ) {
        StringBuilder builder = new StringBuilder();
        appendBaseContext(builder, workspaceSnapshot, selectedSkill, recentTurns, message);
        appendOrchestrationStateSection(builder, orchestrationState);
        appendObservationHistorySection(builder, "【当前请求观察历史】", orchestrationState.observationHistory(), "当前请求尚无结构化观察。");
        appendToolCatalogSection(builder, availableTools);
        appendPlanningInstruction(builder);
        return new AgentPrompt(builder.toString());
    }

    /**
     * 组装最终回复阶段 prompt，在基础上下文之后追加结构化工具观察结果和最终回复约束。
     */
    public AgentPrompt assembleFinalReplyPrompt(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message,
            ToolExecutionResult toolObservation
    ) {
        return assembleFinalReplyPrompt(
                workspaceSnapshot,
                selectedSkill,
                recentTurns,
                message,
                new AgentOrchestrationState(1)
                        .appendObservation(toolObservation)
                        .withTerminalReason(AgentOrchestrationTerminalReason.FINAL_REPLY)
        );
    }

    /**
     * 组装带有完整观察历史和终止信息的最终回复 prompt，使模型能基于累计观察而不是最后一步单点结果来收敛回答。
     */
    public AgentPrompt assembleFinalReplyPrompt(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message,
            AgentOrchestrationState orchestrationState
    ) {
        StringBuilder builder = new StringBuilder();
        appendBaseContext(builder, workspaceSnapshot, selectedSkill, recentTurns, message);
        appendOrchestrationStateSection(builder, orchestrationState);
        appendObservationHistorySection(builder, "【工具观察结果】", orchestrationState.observationHistory(), "当前请求尚无结构化观察。");
        appendFinalReplyInstruction(builder);
        return new AgentPrompt(builder.toString());
    }

    /**
     * 统一拼装 planning/final 阶段共享的基础上下文，保持“静态规则 -> Skill -> 动态记忆 -> 最近会话 -> 当前消息”的稳定顺序。
     */
    private void appendBaseContext(
            StringBuilder builder,
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message
    ) {
        appendWorkspaceSection(builder, "【静态规则】", workspaceSnapshot.staticRules());
        appendSelectedSkillSection(builder, selectedSkill);
        appendWorkspaceSection(builder, "【动态记忆】", workspaceSnapshot.dynamicMemories());
        appendRecentTurnsSection(builder, recentTurns);
        builder.append("【当前用户消息】\n").append(message.body()).append("\n\n");
    }

    /**
     * 统一拼装 workspace 文件段落，保留文件名有助于模型理解上下文来源，也便于测试验证顺序。
     */
    private void appendWorkspaceSection(StringBuilder builder, String sectionTitle, List<WorkspaceFileContent> files) {
        builder.append(sectionTitle).append("\n");
        for (WorkspaceFileContent file : files) {
            builder.append(file.fileName()).append(":\n").append(file.content()).append("\n");
        }
        builder.append("\n");
    }

    /**
     * 仅在 resolver 选中 Skill 时注入对应正文，避免无关请求被额外方法论噪音污染。
     */
    private void appendSelectedSkillSection(StringBuilder builder, Optional<ResolvedSkill> selectedSkill) {
        selectedSkill.ifPresent(skill -> builder.append("【选中的 Skill】\n")
                .append(skill.skillName()).append(":\n")
                .append(skill.instruction()).append("\n\n"));
    }

    /**
     * 以稳定格式追加 recent turns，让模型可以按角色顺序理解当前会话状态。
     */
    private void appendRecentTurnsSection(StringBuilder builder, List<ConversationTurn> recentTurns) {
        builder.append("【最近会话】\n");
        for (ConversationTurn recentTurn : recentTurns) {
            builder.append(recentTurn.role().name()).append(": ").append(recentTurn.content()).append("\n");
        }
        builder.append("\n");
    }

    /**
     * 以稳定文本格式暴露工具目录，确保模型能看到名称、描述和输入 schema 等最小决策信息。
     */
    private void appendToolCatalogSection(StringBuilder builder, List<ToolDefinition> availableTools) {
        builder.append("【可用工具目录】\n");
        if (availableTools.isEmpty()) {
            builder.append("无可用工具。\n\n");
            return;
        }
        for (ToolDefinition toolDefinition : availableTools) {
            builder.append("name: ").append(toolDefinition.name()).append("\n");
            builder.append("description: ").append(toolDefinition.description()).append("\n");
            builder.append("input_schema:\n");
            appendIndentedBlock(builder, toolDefinition.inputSchema().toPrettyJson(), "  ");
            builder.append("\n");
        }
    }

    /**
     * 把多行文本按统一缩进写入 prompt，保证工具 schema 在目录段落中仍保持可读层次。
     */
    private void appendIndentedBlock(StringBuilder builder, String block, String indentation) {
        for (String line : block.split("\\R")) {
            builder.append(indentation).append(line).append("\n");
        }
    }

    /**
     * 把当前请求的编排 budget、当前位置和终止原因暴露给模型，避免其在多步闭环里丢失剩余执行空间感知。
     */
    private void appendOrchestrationStateSection(StringBuilder builder, AgentOrchestrationState orchestrationState) {
        builder.append("【编排状态】\n");
        builder.append("current_step: ").append(orchestrationState.currentStepIndex()).append("\n");
        builder.append("max_steps: ").append(orchestrationState.maxSteps()).append("\n");
        builder.append("remaining_steps: ").append(orchestrationState.remainingSteps()).append("\n");
        if (orchestrationState.terminalReason() != null) {
            builder.append("terminal_reason: ").append(orchestrationState.terminalReason().wireValue()).append("\n");
        }
        builder.append("\n");
    }

    /**
     * 以稳定顺序渲染当前请求的结构化观察历史，确保后续 planning/final-reply 都能看到完整上下文而非最后一步截面。
     */
    private void appendObservationHistorySection(
            StringBuilder builder,
            String sectionTitle,
            List<AgentObservation> observationHistory,
            String emptyMessage
    ) {
        builder.append(sectionTitle).append("\n");
        if (observationHistory.isEmpty()) {
            builder.append(emptyMessage).append("\n\n");
            return;
        }
        for (AgentObservation observation : observationHistory) {
            builder.append("step: ").append(observation.stepIndex()).append("\n");
            appendObservation(builder, observation.result());
            builder.append("\n");
        }
    }

    /**
     * 以稳定结构渲染单个工具观察结果，使 prompt 在 success/error 两类路径下都保持统一可读格式。
     */
    private void appendObservation(StringBuilder builder, ToolExecutionResult observation) {
        if (observation instanceof ToolExecutionSuccess success) {
            builder.append("tool_name: ").append(success.toolName()).append("\n");
            builder.append("status: success\n");
            builder.append("payload: ").append(success.payload()).append("\n");
            return;
        }
        ToolExecutionError error = (ToolExecutionError) observation;
        builder.append("tool_name: ").append(error.toolName()).append("\n");
        builder.append("status: error\n");
        builder.append("error_code: ").append(error.errorCode()).append("\n");
        builder.append("message: ").append(error.message()).append("\n");
        builder.append("details: ").append(error.details()).append("\n");
    }

    /**
     * 追加规划阶段输出契约，约束模型只能返回一个 JSON 决策对象而不是自由文本。
     */
    private void appendPlanningInstruction(StringBuilder builder) {
        builder.append("【决策输出要求】\n");
        builder.append("请只输出一个 JSON 对象，不要输出 Markdown 代码块或额外解释。\n");
        builder.append("若已足够直接回答用户，请输出：{\"type\":\"final_reply\",\"reply\":\"...\"}\n");
        builder.append("若需要调用一个工具，请输出：{\"type\":\"tool_call\",\"toolName\":\"工具名\",\"arguments\":{}}\n");
        builder.append("本次请求允许在剩余 step budget 内继续同步工具闭环，但每一步仍只能输出一个下一步动作。\n");
    }

    /**
     * 追加最终回复阶段约束，要求模型只输出给用户的正文而不再返回工具决策 JSON。
     */
    private void appendFinalReplyInstruction(StringBuilder builder) {
        builder.append("【最终回复要求】\n");
        builder.append("请基于以上上下文和工具观察结果，直接输出给用户的最终回复正文。\n");
        builder.append("不要输出 JSON、不要重复工具目录、不要解释你的内部推理。\n");
    }
}
