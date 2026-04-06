package com.quashy.openclaw4j.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 约束第一阶段包边界重构的目标结构，避免根包和横向杂包在后续调整中回流。
 */
class PackageStructureSmokeTest {

    /**
     * 验证代表性入口、共享会话模型与关键应用边界已经落在目标包名下，并且旧包名不再继续承载这些职责。
     */
    @Test
    void shouldExposeRepresentativeTypesInTargetPackages() {
        assertDoesNotThrow(() -> Class.forName("com.quashy.openclaw4j.debug.ChatClientController"));
        assertDoesNotThrow(() -> Class.forName("com.quashy.openclaw4j.conversation.InternalConversationId"));
        assertDoesNotThrow(() -> Class.forName("com.quashy.openclaw4j.agent.application.AgentFacade"));
        assertDoesNotThrow(() -> Class.forName("com.quashy.openclaw4j.channel.proactive.ProactiveConversationSender"));
        assertDoesNotThrow(() -> Class.forName("com.quashy.openclaw4j.reminder.application.ReminderService"));

        assertTrue(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/debug/ChatClientController.java")));
        assertTrue(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/conversation/InternalConversationId.java")));
        assertTrue(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/agent/application/AgentFacade.java")));
        assertTrue(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/channel/proactive/ProactiveConversationSender.java")));
        assertTrue(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/reminder/application/ReminderService.java")));

        assertFalse(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/ChatClientController.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/domain/InternalConversationId.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/agent/api/AgentFacade.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/channel/outbound/ProactiveConversationSender.java")));
        assertFalse(Files.exists(Path.of("src/main/java/com/quashy/openclaw4j/reminder/ReminderService.java")));
    }
}
