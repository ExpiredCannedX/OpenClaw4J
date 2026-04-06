package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.channel.dm.DirectMessageIngressCommand;
import com.quashy.openclaw4j.channel.dm.DirectMessageHandleResult;
import com.quashy.openclaw4j.channel.dm.DirectMessageService;
import com.quashy.openclaw4j.agent.model.ReplyEnvelope;
import com.quashy.openclaw4j.observability.model.RuntimeObservationLevel;
import com.quashy.openclaw4j.observability.model.RuntimeObservationPhase;
import com.quashy.openclaw4j.observability.model.TraceContext;
import com.quashy.openclaw4j.observability.port.RuntimeObservationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排 Telegram webhook 的协议翻译与回复投递，只负责把受支持的 Telegram 私聊文本桥接到统一单聊 ingress。
 */
@Service
@ConditionalOnProperty(prefix = "openclaw.telegram", name = "enabled", havingValue = "true")
public class TelegramWebhookService {

    /**
     * 记录 Telegram adapter 在忽略 update 或出站失败时的运行信息，便于后续联调和运维排查。
     */
    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookService.class);

    /**
     * 平台无关的统一单聊 ingress 服务，负责身份映射、会话复用与幂等逻辑。
     */
    private final DirectMessageService directMessageService;

    /**
     * 负责创建 Telegram 入站到出站全链路共享的 trace，并在适配器边界发出结构化事件。
     */
    private final RuntimeObservationPublisher runtimeObservationPublisher;

    /**
     * Telegram 文本回复出站端口，隔离 webhook 编排与具体 Bot API 发送实现。
     */
    private final TelegramOutboundClient telegramOutboundClient;

    /**
     * 通过统一 ingress 服务和 Telegram 出站端口构建最小闭环，保持核心单聊逻辑不感知 Telegram 协议。
     */
    public TelegramWebhookService(
            DirectMessageService directMessageService,
            TelegramOutboundClient telegramOutboundClient,
            RuntimeObservationPublisher runtimeObservationPublisher
    ) {
        this.directMessageService = directMessageService;
        this.telegramOutboundClient = telegramOutboundClient;
        this.runtimeObservationPublisher = runtimeObservationPublisher;
    }

    /**
     * 处理单个 Telegram webhook update；仅当 update 是受支持的私聊文本消息时才进入统一单聊主链路。
     */
    public void handle(TelegramUpdate update) {
        TraceContext traceContext = runtimeObservationPublisher.createTrace(
                "telegram",
                extractExternalConversationId(update),
                extractExternalMessageId(update)
        );
        runtimeObservationPublisher.emit(
                traceContext,
                "telegram.update.received",
                RuntimeObservationPhase.INGRESS,
                RuntimeObservationLevel.INFO,
                "TelegramWebhookService",
                buildReceivePayload(update)
        );
        TelegramInboundTextMessage inboundMessage = extractPrivateTextMessage(update);
        if (inboundMessage == null) {
            runtimeObservationPublisher.emit(
                    traceContext,
                    "telegram.update.ignored",
                    RuntimeObservationPhase.INGRESS,
                    RuntimeObservationLevel.INFO,
                    "TelegramWebhookService",
                    Map.of("reason", "unsupported_update")
            );
            log.debug("忽略不受支持的 Telegram update: updateId={}", update != null ? update.updateId() : null);
            return;
        }
        DirectMessageHandleResult handleResult = directMessageService.handleWithMetadata(new DirectMessageIngressCommand(
                "telegram",
                inboundMessage.externalUserId(),
                inboundMessage.externalConversationId(),
                inboundMessage.externalMessageId(),
                inboundMessage.body()
        ), traceContext);
        if (!handleResult.newlyProcessed()) {
            runtimeObservationPublisher.emit(
                    handleResult.traceContext(),
                    "telegram.outbound.skipped_duplicate",
                    RuntimeObservationPhase.OUTBOUND,
                    RuntimeObservationLevel.WARN,
                    "TelegramWebhookService",
                    Map.of("reason", "duplicate_delivery")
            );
            log.debug("忽略重复 Telegram update 的重复发送: updateId={}, chatId={}", inboundMessage.externalMessageId(), inboundMessage.chatId());
            return;
        }
        ReplyEnvelope replyEnvelope = handleResult.replyEnvelope();
        try {
            runtimeObservationPublisher.emit(
                    handleResult.traceContext(),
                    "telegram.outbound.send_started",
                    RuntimeObservationPhase.OUTBOUND,
                    RuntimeObservationLevel.INFO,
                    "TelegramWebhookService",
                    Map.of("chatId", inboundMessage.chatId())
            );
            telegramOutboundClient.sendMessage(new TelegramOutboundMessage(inboundMessage.chatId(), replyEnvelope.body()));
            runtimeObservationPublisher.emit(
                    handleResult.traceContext(),
                    "telegram.outbound.sent",
                    RuntimeObservationPhase.OUTBOUND,
                    RuntimeObservationLevel.INFO,
                    "TelegramWebhookService",
                    Map.of(
                            "chatId", inboundMessage.chatId(),
                            "replyLength", replyEnvelope.body().length()
                    )
            );
        } catch (RuntimeException exception) {
            runtimeObservationPublisher.emit(
                    handleResult.traceContext(),
                    "telegram.outbound.failed",
                    RuntimeObservationPhase.OUTBOUND,
                    RuntimeObservationLevel.ERROR,
                    "TelegramWebhookService",
                    Map.of(
                            "chatId", inboundMessage.chatId(),
                            "exceptionType", exception.getClass().getSimpleName()
                    ),
                    buildExceptionVerbosePayload(exception)
            );
            log.warn("Telegram 私聊回复发送失败: updateId={}, chatId={}", inboundMessage.externalMessageId(), inboundMessage.chatId(), exception);
        }
    }

    /**
     * 从 Telegram update 中提取当前首版真正支持的“私聊文本消息”，其余事件统一视为可安全忽略。
     */
    private TelegramInboundTextMessage extractPrivateTextMessage(TelegramUpdate update) {
        if (update == null || update.message() == null || update.updateId() == null) {
            return null;
        }
        TelegramUpdate.TelegramMessage message = update.message();
        if (message.chat() == null || message.from() == null || message.chat().id() == null || message.from().id() == null) {
            return null;
        }
        if (!"private".equals(message.chat().type()) || !StringUtils.hasText(message.text())) {
            return null;
        }
        return new TelegramInboundTextMessage(
                String.valueOf(message.from().id()),
                String.valueOf(message.chat().id()),
                String.valueOf(update.updateId()),
                message.chat().id(),
                message.text()
        );
    }

    /**
     * 从 Telegram update 中尽量提取外部会话标识，让被忽略的 update 也能拥有最小 trace 关联信息。
     */
    private String extractExternalConversationId(TelegramUpdate update) {
        if (update == null || update.message() == null || update.message().chat() == null || update.message().chat().id() == null) {
            return null;
        }
        return String.valueOf(update.message().chat().id());
    }

    /**
     * 从 Telegram update 中提取外部消息标识，优先复用 `update_id` 以保持与幂等键口径一致。
     */
    private String extractExternalMessageId(TelegramUpdate update) {
        if (update == null || update.updateId() == null) {
            return null;
        }
        return String.valueOf(update.updateId());
    }

    /**
     * 为接收事件构造最小摘要负载，帮助控制台快速判断当前 webhook 是否命中了支持的消息类型。
     */
    private Map<String, Object> buildReceivePayload(TelegramUpdate update) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (update != null && update.updateId() != null) {
            payload.put("updateId", update.updateId());
        }
        if (update != null && update.message() != null && update.message().chat() != null && update.message().chat().type() != null) {
            payload.put("chatType", update.message().chat().type());
        }
        return Map.copyOf(payload);
    }

    /**
     * 仅把异常文本暴露给详细模式，避免默认时间线输出过多 Telegram 出站错误细节。
     */
    private Map<String, Object> buildExceptionVerbosePayload(RuntimeException exception) {
        if (!StringUtils.hasText(exception.getMessage())) {
            return Map.of();
        }
        return Map.of("exceptionMessage", exception.getMessage());
    }

    /**
     * 表示 Telegram adapter 提取出的最小受支持入站消息，避免在编排方法中重复解构 update 层级。
     */
    private record TelegramInboundTextMessage(
            /**
             * 统一 ingress 所需的外部用户标识，直接来自 Telegram `from.id`。
             */
            String externalUserId,
            /**
             * 统一 ingress 所需的外部会话标识，直接来自 Telegram `chat.id`。
             */
            String externalConversationId,
            /**
             * 统一 ingress 所需的外部消息幂等标识，直接来自 Telegram `update_id`。
             */
            String externalMessageId,
            /**
             * Telegram 出站发送所需的原始 chat 标识，用于把最终回复送回正确私聊。
             */
            Long chatId,
            /**
             * 进入统一单聊主链路的文本正文，只接受非空白私聊文本。
             */
            String body
    ) {
    }
}
