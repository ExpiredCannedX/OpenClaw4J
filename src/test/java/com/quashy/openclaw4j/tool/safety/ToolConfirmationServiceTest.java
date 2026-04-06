package com.quashy.openclaw4j.tool.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.domain.InternalConversationId;
import com.quashy.openclaw4j.domain.InternalUserId;
import com.quashy.openclaw4j.domain.NormalizedDirectMessage;
import com.quashy.openclaw4j.observability.model.RuntimeObservationMode;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationResolution;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationResolutionStatus;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolConfirmationService;
import com.quashy.openclaw4j.tool.safety.confirmation.ToolPendingConfirmationRecord;
import com.quashy.openclaw4j.tool.safety.infrastructure.sqlite.SqliteToolSafetyRepository;
import com.quashy.openclaw4j.tool.safety.model.ToolArgumentValidatorType;
import com.quashy.openclaw4j.tool.safety.model.ToolConfirmationPolicy;
import com.quashy.openclaw4j.tool.safety.model.ToolRiskLevel;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证显式确认流会把待确认请求持久化为稳定状态机，并只允许同会话同用户恢复原始请求。
 */
class ToolConfirmationServiceTest {

    /**
     * 同会话显式确认命中待确认项时，服务必须返回可恢复执行的原始工具请求而不是要求模型重新规划。
     */
    @Test
    void shouldResolveExplicitConfirmationToStoredToolRequest(@TempDir Path tempDir) {
        Clock requestClock = Clock.fixed(Instant.parse("2026-04-06T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        SqliteToolSafetyRepository repository = createRepository(tempDir, requestClock);
        ToolConfirmationService requestPhaseService = createService(repository, requestClock);
        ToolCallRequest originalRequest = createRequest(
                tempDir,
                "mcp.filesystem.write_file",
                Map.of("path", "docs/notes.md", "content", "hello")
        );
        ToolPendingConfirmationRecord pendingRecord = requestPhaseService.createPendingConfirmation(
                originalRequest,
                new ToolSafetyProfile(
                        ToolRiskLevel.DESTRUCTIVE,
                        ToolConfirmationPolicy.EXPLICIT,
                        ToolArgumentValidatorType.FILESYSTEM_WRITE
                ),
                "filesystem_write_requires_confirmation"
        );

        Clock confirmClock = Clock.fixed(Instant.parse("2026-04-06T08:03:00Z"), ZoneId.of("Asia/Shanghai"));
        ToolConfirmationService confirmPhaseService = createService(repository, confirmClock);
        ToolConfirmationResolution resolution = confirmPhaseService.resolveExplicitConfirmation(
                originalRequest.executionContext(),
                "确认"
        );

        assertThat(resolution.status()).isEqualTo(ToolConfirmationResolutionStatus.RESUMABLE);
        assertThat(resolution.requestToResume()).isNotNull();
        assertThat(resolution.requestToResume().toolName()).isEqualTo(originalRequest.toolName());
        assertThat(resolution.requestToResume().arguments()).isEqualTo(originalRequest.arguments());
        assertThat(resolution.requestToResume().executionContext().confirmedPendingRequestId())
                .isEqualTo(pendingRecord.confirmationId());
    }

    /**
     * 待确认请求过期后，即使收到合法确认短语也必须拒绝恢复执行，避免旧授权被延迟滥用。
     */
    @Test
    void shouldRejectExpiredConfirmation(@TempDir Path tempDir) {
        Clock requestClock = Clock.fixed(Instant.parse("2026-04-06T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        SqliteToolSafetyRepository repository = createRepository(tempDir, requestClock);
        ToolConfirmationService requestPhaseService = createService(repository, requestClock);
        ToolCallRequest originalRequest = createRequest(
                tempDir,
                "mcp.filesystem.write_file",
                Map.of("path", "docs/notes.md", "content", "hello")
        );
        requestPhaseService.createPendingConfirmation(
                originalRequest,
                new ToolSafetyProfile(
                        ToolRiskLevel.DESTRUCTIVE,
                        ToolConfirmationPolicy.EXPLICIT,
                        ToolArgumentValidatorType.FILESYSTEM_WRITE
                ),
                "filesystem_write_requires_confirmation"
        );

        Clock confirmClock = Clock.fixed(Instant.parse("2026-04-06T08:15:01Z"), ZoneId.of("Asia/Shanghai"));
        ToolConfirmationService confirmPhaseService = createService(repository, confirmClock);
        ToolConfirmationResolution resolution = confirmPhaseService.resolveExplicitConfirmation(
                originalRequest.executionContext(),
                "确认"
        );

        assertThat(resolution.status()).isEqualTo(ToolConfirmationResolutionStatus.REJECTED);
        assertThat(resolution.reasonCode()).isEqualTo("confirmation_expired");
        assertThat(resolution.requestToResume()).isNull();
    }

    /**
     * 基于临时目录创建独立 SQLite 仓储，确保确认状态测试不共享任何外部持久化数据。
     */
    private SqliteToolSafetyRepository createRepository(Path tempDir, Clock clock) {
        return new SqliteToolSafetyRepository(
                tempDir.resolve(".openclaw/tool-safety.sqlite"),
                new ObjectMapper(),
                clock
        );
    }

    /**
     * 统一创建确认流服务，保证每个测试都使用同一组确认短语、过期窗口和敏感路径配置。
     */
    private ToolConfirmationService createService(SqliteToolSafetyRepository repository, Clock clock) {
        return new ToolConfirmationService(
                repository,
                repository,
                new OpenClawProperties.ToolSafetyProperties(
                        ".openclaw/tool-safety.sqlite",
                        Duration.ofMinutes(10),
                        List.of("确认", "继续", "yes", "confirm"),
                        List.of("AGENTS.md", "SOUL.md", "SKILLS.md")
                ),
                new ObjectMapper(),
                clock
        );
    }

    /**
     * 构造一个带最小上下文的工具请求，使确认流能够绑定内部用户、会话和 workspace 根目录事实。
     */
    private ToolCallRequest createRequest(Path workspaceRoot, String toolName, Map<String, Object> arguments) {
        return new ToolCallRequest(
                toolName,
                arguments,
                new ToolExecutionContext(
                        new InternalUserId("user-1"),
                        new InternalConversationId("conversation-1"),
                        new NormalizedDirectMessage("dev", "external-user-1", "external-conversation-1", "message-1", "请执行工具"),
                        new TraceContext("run-1", "dev", "external-conversation-1", "message-1", "conversation-1", RuntimeObservationMode.OFF),
                        workspaceRoot
                )
        );
    }
}
