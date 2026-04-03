package com.quashy.openclaw4j.agent;

import com.quashy.openclaw4j.domain.ConversationTurn;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.workspace.WorkspaceFileContent;
import com.quashy.openclaw4j.workspace.WorkspaceSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 负责把 workspace 内容和 recent turns 以确定顺序组装为模型输入，保证上下文来源清晰且可测试。
 */
@Component
public class AgentPromptAssembler {

    /**
     * 按“静态规则 -> 动态记忆 -> 最近会话 -> 当前消息”的顺序组装提示词，使当前最小主链路与设计文档保持一致。
     */
    public AgentPrompt assemble(WorkspaceSnapshot workspaceSnapshot, List<ConversationTurn> recentTurns, NormalizedDirectMessage message) {
        StringBuilder builder = new StringBuilder();
        builder.append("【静态规则】\n").append(workspaceSnapshot.staticRules().content()).append("\n\n");
        builder.append("【动态记忆】\n");
        for (WorkspaceFileContent dynamicMemory : workspaceSnapshot.dynamicMemories()) {
            builder.append(dynamicMemory.fileName()).append(":\n").append(dynamicMemory.content()).append("\n");
        }
        builder.append("\n【最近会话】\n");
        for (ConversationTurn recentTurn : recentTurns) {
            builder.append(recentTurn.role().name()).append(": ").append(recentTurn.content()).append("\n");
        }
        builder.append("\n【当前用户消息】\n").append(message.body());
        return new AgentPrompt(builder.toString());
    }
}
