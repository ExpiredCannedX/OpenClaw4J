package com.quashy.openclaw4j.tool.safety.confirmation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.quashy.openclaw4j.config.OpenClawProperties;
import com.quashy.openclaw4j.tool.model.ToolCallRequest;
import com.quashy.openclaw4j.tool.model.ToolExecutionContext;
import com.quashy.openclaw4j.tool.safety.audit.ToolAuditLogEntry;
import com.quashy.openclaw4j.tool.safety.model.ToolSafetyProfile;
import com.quashy.openclaw4j.tool.safety.port.ToolAuditLogRepository;
import com.quashy.openclaw4j.tool.safety.port.ToolConfirmationRepository;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责待确认请求的持久化状态流转、显式确认解析和恢复执行所需的原始工具请求重建。
 */
public class ToolConfirmationService {

    /**
     * 提供待确认状态持久化能力，使确认流不依赖进程内存。
     */
    private final ToolConfirmationRepository confirmationRepository;

    /**
     * 负责追加结构化审计事件，确保确认创建、确认消费和拒绝都可追溯。
     */
    private final ToolAuditLogRepository auditLogRepository;

    /**
     * 提供确认过期时间和显式确认短语等集中配置。
     */
    private final OpenClawProperties.ToolSafetyProperties properties;

    /**
     * 用于稳定序列化工具参数，以生成参数指纹并在恢复执行时重建原始请求。
     */
    private final ObjectMapper objectMapper;

    /**
     * 提供统一时间源，使确认过期与状态流转可被稳定测试。
     */
    private final Clock clock;

    /**
     * 通过显式依赖注入固定确认流依赖，保持服务职责集中于状态机和恢复逻辑本身。
     */
    public ToolConfirmationService(
            ToolConfirmationRepository confirmationRepository,
            ToolAuditLogRepository auditLogRepository,
            OpenClawProperties.ToolSafetyProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        Assert.notNull(confirmationRepository, "confirmationRepository must not be null");
        Assert.notNull(auditLogRepository, "auditLogRepository must not be null");
        Assert.notNull(properties, "properties must not be null");
        Assert.notNull(objectMapper, "objectMapper must not be null");
        Assert.notNull(clock, "clock must not be null");
        this.confirmationRepository = confirmationRepository;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.clock = clock;
    }

    /**
     * 为高风险请求创建待确认记录，并通过持久化状态确保后续显式确认只依赖服务端事实。
     */
    public ToolPendingConfirmationRecord createPendingConfirmation(
            ToolCallRequest request,
            ToolSafetyProfile safetyProfile,
            String reasonCode
    ) {
        Assert.notNull(request.executionContext(), "executionContext must not be null");
        Instant now = Instant.now(clock);
        Optional<ToolPendingConfirmationRecord> activeConfirmation = confirmationRepository.findActiveConfirmation(
                request.executionContext().conversationId(),
                request.executionContext().userId(),
                now
        );
        String normalizedArgumentsJson = normalizeArguments(request.arguments());
        String argumentsFingerprint = fingerprint(normalizedArgumentsJson);
        if (activeConfirmation.isPresent()) {
            return activeConfirmation.get();
        }
        ToolPendingConfirmationRecord record = new ToolPendingConfirmationRecord(
                UUID.randomUUID().toString(),
                request.executionContext().conversationId(),
                request.executionContext().userId(),
                request.toolName(),
                normalizedArgumentsJson,
                argumentsFingerprint,
                safetyProfile.riskLevel(),
                safetyProfile.confirmationPolicy(),
                safetyProfile.validatorType(),
                ToolConfirmationStatus.PENDING,
                buildRiskSummary(request, safetyProfile),
                now,
                now.plus(properties.confirmationTtl()),
                null,
                null
        );
        confirmationRepository.upsertConfirmation(record);
        appendAudit(record, null, record.status().name(), null, reasonCode, Map.of("riskSummary", record.riskSummary()));
        return record;
    }

    /**
     * 判断当前恢复执行请求是否已经命中同一条已确认记录，避免短路恢复再次被策略层挡回待确认。
     */
    public boolean isExecutionConfirmed(ToolCallRequest request) {
        if (request.executionContext() == null || request.executionContext().confirmedPendingRequestId() == null) {
            return false;
        }
        Optional<ToolPendingConfirmationRecord> confirmation = confirmationRepository.findConfirmationById(
                request.executionContext().confirmedPendingRequestId()
        );
        if (confirmation.isEmpty()) {
            return false;
        }
        ToolPendingConfirmationRecord record = confirmation.get();
        if (record.status() != ToolConfirmationStatus.CONFIRMED) {
            return false;
        }
        if (record.expiresAt().isBefore(Instant.now(clock))) {
            return false;
        }
        if (!record.toolName().equals(request.toolName())) {
            return false;
        }
        return record.argumentsFingerprint().equals(fingerprint(normalizeArguments(request.arguments())));
    }

    /**
     * 把当前消息解析为显式确认，并在命中待确认项时返回可短路恢复执行的原始工具请求。
     */
    public ToolConfirmationResolution resolveExplicitConfirmation(
            ToolExecutionContext executionContext,
            String messageBody
    ) {
        Assert.notNull(executionContext, "executionContext must not be null");
        if (!isExplicitConfirmationMessage(messageBody)) {
            return ToolConfirmationResolution.noMatch();
        }
        Optional<ToolPendingConfirmationRecord> latestConfirmation = confirmationRepository.findLatestConfirmation(
                executionContext.conversationId(),
                executionContext.userId()
        );
        if (latestConfirmation.isEmpty()) {
            return ToolConfirmationResolution.rejected(
                    "no_pending_confirmation",
                    "There is no pending tool request waiting for confirmation.",
                    null
            );
        }
        ToolPendingConfirmationRecord record = latestConfirmation.get();
        Instant now = Instant.now(clock);
        if (record.expiresAt().isBefore(now)) {
            ToolPendingConfirmationRecord expiredRecord = record.withStatus(
                    ToolConfirmationStatus.EXPIRED,
                    record.confirmedAt(),
                    record.consumedAt()
            );
            confirmationRepository.upsertConfirmation(expiredRecord);
            appendAudit(expiredRecord, null, expiredRecord.status().name(), null, "confirmation_expired", Map.of());
            return ToolConfirmationResolution.rejected(
                    "confirmation_expired",
                    "The pending tool request has expired.",
                    expiredRecord
            );
        }
        ToolPendingConfirmationRecord confirmedRecord = record.status() == ToolConfirmationStatus.CONFIRMED
                ? record
                : record.withStatus(ToolConfirmationStatus.CONFIRMED, now, record.consumedAt());
        confirmationRepository.upsertConfirmation(confirmedRecord);
        appendAudit(confirmedRecord, null, confirmedRecord.status().name(), null, "confirmation_confirmed", Map.of());
        return ToolConfirmationResolution.resumable(
                confirmedRecord,
                new ToolCallRequest(
                        confirmedRecord.toolName(),
                        parseArguments(confirmedRecord.normalizedArgumentsJson()),
                        executionContext.withConfirmedPendingRequestId(confirmedRecord.confirmationId())
                )
        );
    }

    /**
     * 在确认短路执行完成后把记录转为已消费，避免同一条高风险请求被重复恢复执行。
     */
    public void markConsumed(ToolCallRequest request, String executionOutcome, String reasonCode, Map<String, Object> details) {
        if (request.executionContext() == null || request.executionContext().confirmedPendingRequestId() == null) {
            return;
        }
        confirmationRepository.findConfirmationById(request.executionContext().confirmedPendingRequestId())
                .ifPresent(record -> {
                    ToolPendingConfirmationRecord consumedRecord = record.withStatus(
                            ToolConfirmationStatus.CONSUMED,
                            record.confirmedAt(),
                            Instant.now(clock)
                    );
                    confirmationRepository.upsertConfirmation(consumedRecord);
                    appendAudit(consumedRecord, null, consumedRecord.status().name(), executionOutcome, reasonCode, details);
                });
    }

    /**
     * 判断当前消息正文是否属于允许消费待确认项的显式确认短语集合。
     */
    private boolean isExplicitConfirmationMessage(String messageBody) {
        if (messageBody == null) {
            return false;
        }
        String normalizedMessage = normalizeConfirmationText(messageBody);
        return properties.confirmationPhrases().stream()
                .map(this::normalizeConfirmationText)
                .anyMatch(normalizedMessage::equals);
    }

    /**
     * 生成稳定风险摘要，便于用户提示和审计日志理解当前待确认请求为何被保护。
     */
    private String buildRiskSummary(ToolCallRequest request, ToolSafetyProfile safetyProfile) {
        return safetyProfile.riskLevel().name().toLowerCase(Locale.ROOT) + ":" + request.toolName();
    }

    /**
     * 以稳定键顺序序列化参数，确保相同请求在不同 JVM 运行中仍能生成一致指纹。
     */
    private String normalizeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to normalize tool arguments.", exception);
        }
    }

    /**
     * 从稳定 JSON 生成 SHA-256 指纹，使确认恢复和审计日志都能引用同一不可变请求摘要。
     */
    private String fingerprint(String normalizedArgumentsJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedArgumentsJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to compute tool argument fingerprint.", exception);
        }
    }

    /**
     * 把持久化 JSON 恢复为稳定参数映射，供确认后的短路恢复执行直接复用。
     */
    private Map<String, Object> parseArguments(String normalizedArgumentsJson) {
        try {
            return objectMapper.readValue(normalizedArgumentsJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse stored tool arguments.", exception);
        }
    }

    /**
     * 统一裁剪确认短语中的空白和常见尾部标点，使“确认!”和“确认”拥有相同语义。
     */
    private String normalizeConfirmationText(String value) {
        return value.trim()
                .replaceAll("[。！？!?,，.]+$", "")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 追加结构化审计事件，保证确认流中的关键状态转换都能被后续回放和排查。
     */
    private void appendAudit(
            ToolPendingConfirmationRecord record,
            String policyDecision,
            String confirmationStatus,
            String executionOutcome,
            String reasonCode,
            Map<String, Object> details
    ) {
        auditLogRepository.appendAuditLog(new ToolAuditLogEntry(
                "confirmation_transition",
                record.confirmationId(),
                record.conversationId(),
                record.userId(),
                record.toolName(),
                record.argumentsFingerprint(),
                policyDecision,
                confirmationStatus,
                executionOutcome,
                reasonCode,
                details,
                Instant.now(clock)
        ));
    }
}

