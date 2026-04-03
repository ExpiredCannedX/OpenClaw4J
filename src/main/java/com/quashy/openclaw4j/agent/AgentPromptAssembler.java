package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.skill.ResolvedSkill;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 负责把 workspace 内容和 recent turns 以确定顺序组装为模型输入，保证上下文来源清晰且可测试。
 */
@Component
public class AgentPromptAssembler {

    /**
     * 按“静态规则 -> 选中的 Skill -> 动态记忆 -> 最近会话 -> 当前消息”的顺序组装提示词，使上下文层次与设计文档保持一致。
     */
    public AgentPrompt assemble(
            WorkspaceSnapshot workspaceSnapshot,
            Optional<ResolvedSkill> selectedSkill,
            List<ConversationTurn> recentTurns,
            NormalizedDirectMessage message
    ) {
        StringBuilder builder = new StringBuilder();
        appendWorkspaceSection(builder, "【静态规则】", workspaceSnapshot.staticRules());
        appendSelectedSkillSection(builder, selectedSkill);
        appendWorkspaceSection(builder, "【动态记忆】", workspaceSnapshot.dynamicMemories());
        appendRecentTurnsSection(builder, recentTurns);
        builder.append("【当前用户消息】\n").append(message.body());
        return new AgentPrompt(builder.toString());
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
}
