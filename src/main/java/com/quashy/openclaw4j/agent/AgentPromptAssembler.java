package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.skill.ResolvedSkill;
import com.quashy.openclaw4j.tool.ToolDefinition;
import com.quashy.openclaw4j.tool.ToolExecutionError;
import com.quashy.openclaw4j.tool.ToolExecutionResult;
import com.quashy.openclaw4j.tool.ToolExecutionSuccess;
import com.quashy.openclaw4j.tool.ToolInputProperty;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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
        StringBuilder builder = new StringBuilder();
        appendBaseContext(builder, workspaceSnapshot, selectedSkill, recentTurns, message);
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
        StringBuilder builder = new StringBuilder();
        appendBaseContext(builder, workspaceSnapshot, selectedSkill, recentTurns, message);
        appendToolObservationSection(builder, toolObservation);
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
            builder.append("  type: ").append(toolDefinition.inputSchema().type()).append("\n");
            builder.append("  requires: ").append(toolDefinition.inputSchema().required()).append("\n");
            appendToolProperties(builder, toolDefinition.inputSchema().properties());
            builder.append("\n");
        }
    }

    /**
     * 追加工具入参属性说明，帮助模型理解参数名、类型和业务含义。
     */
    private void appendToolProperties(StringBuilder builder, Map<String, ToolInputProperty> properties) {
        if (properties.isEmpty()) {
            builder.append("  properties: {}\n");
            return;
        }
        builder.append("  properties:\n");
        for (Map.Entry<String, ToolInputProperty> entry : properties.entrySet()) {
            builder.append("    - name: ").append(entry.getKey()).append("\n");
            builder.append("      type: ").append(entry.getValue().type()).append("\n");
            builder.append("      description: ").append(entry.getValue().description()).append("\n");
        }
    }

    /**
     * 以稳定结构回填一次工具观察结果，让最终回复阶段可以安全理解工具成功或失败信息。
     */
    private void appendToolObservationSection(StringBuilder builder, ToolExecutionResult toolObservation) {
        builder.append("【工具观察结果】\n");
        if (toolObservation instanceof ToolExecutionSuccess success) {
            builder.append("tool_name: ").append(success.toolName()).append("\n");
            builder.append("status: success\n");
            builder.append("payload: ").append(success.payload()).append("\n\n");
            return;
        }
        ToolExecutionError error = (ToolExecutionError) toolObservation;
        builder.append("tool_name: ").append(error.toolName()).append("\n");
        builder.append("status: error\n");
        builder.append("error_code: ").append(error.errorCode()).append("\n");
        builder.append("message: ").append(error.message()).append("\n");
        builder.append("details: ").append(error.details()).append("\n\n");
    }

    /**
     * 追加规划阶段输出契约，约束模型只能返回一个 JSON 决策对象而不是自由文本。
     */
    private void appendPlanningInstruction(StringBuilder builder) {
        builder.append("【决策输出要求】\n");
        builder.append("请只输出一个 JSON 对象，不要输出 Markdown 代码块或额外解释。\n");
        builder.append("若已足够直接回答用户，请输出：{\"type\":\"final_reply\",\"reply\":\"...\"}\n");
        builder.append("若需要调用一个工具，请输出：{\"type\":\"tool_call\",\"toolName\":\"工具名\",\"arguments\":{}}\n");
        builder.append("本次请求至多允许一次工具调用。\n");
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
