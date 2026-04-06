package com.quashy.openclaw4j.conversation.infrastructure.sqlite;

import com.quashy.openclaw4j.conversation.ConversationDeliveryTarget;
import com.quashy.openclaw4j.conversation.InternalConversationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证会话回发目标 SQLite 仓储会按内部会话维度稳定 upsert 绑定，并在仓储重建后仍能恢复最新目标。
 */
class SqliteConversationDeliveryTargetRepositoryTest {

    /**
     * 临时目录用于隔离测试专用 SQLite 文件，避免不同用例的目标绑定相互污染。
     */
    @TempDir
    Path tempDir;

    /**
     * 同一内部会话重复刷新目标时，仓储必须覆盖为最新渠道目标，并在重建仓储实例后仍能读取到更新结果。
     */
    @Test
    void shouldUpsertAndReloadConversationDeliveryTargetAcrossRepositoryRecreation() {
        Path databaseFile = tempDir.resolve(".openclaw/reminders.sqlite");
        SqliteConversationDeliveryTargetRepository firstRepository = new SqliteConversationDeliveryTargetRepository(databaseFile, fixedClock());
        InternalConversationId conversationId = new InternalConversationId("conversation-1");

        firstRepository.save(new ConversationDeliveryTarget(
                conversationId,
                "telegram",
                "2001",
                OffsetDateTime.parse("2026-04-05T10:00:00+08:00")
        ));
        firstRepository.save(new ConversationDeliveryTarget(
                conversationId,
                "telegram",
                "2002",
                OffsetDateTime.parse("2026-04-05T10:01:00+08:00")
        ));

        SqliteConversationDeliveryTargetRepository restartedRepository = new SqliteConversationDeliveryTargetRepository(databaseFile, fixedClock());

        assertThat(restartedRepository.findByConversationId(conversationId))
                .hasValueSatisfying(target -> {
                    assertThat(target.conversationId()).isEqualTo(conversationId);
                    assertThat(target.channel()).isEqualTo("telegram");
                    assertThat(target.externalConversationId()).isEqualTo("2002");
                    assertThat(target.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-04-05T10:01:00+08:00"));
                });
    }

    /**
     * 固定时钟让仓储在需要回退到当前时间时保持可预测行为，也避免不同执行环境带来隐式时间偏差。
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-04-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}