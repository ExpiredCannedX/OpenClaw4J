package com.quashy.openclaw4j.channel.telegram;

import com.quashy.openclaw4j.channel.dm.DirectMessageIngressCommand;
import com.quashy.openclaw4j.channel.dm.DirectMessageService;
import com.quashy.openclaw4j.domain.ReplyEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
     * Telegram 文本回复出站端口，隔离 webhook 编排与具体 Bot API 发送实现。
     */
    private final TelegramOutboundClient telegramOutboundClient;

    /**
     * 通过统一 ingress 服务和 Telegram 出站端口构建最小闭环，保持核心单聊逻辑不感知 Telegram 协议。
     */
    public TelegramWebhookService(DirectMessageService directMessageService, TelegramOutboundClient telegramOutboundClient) {
        this.directMessageService = directMessageService;
        this.telegramOutboundClient = telegramOutboundClient;
    }

    /**
     * 处理单个 Telegram webhook update；仅当 update 是受支持的私聊文本消息时才进入统一单聊主链路。
     */
    public void handle(TelegramUpdate update) {
        TelegramInboundTextMessage inboundMessage = extractPrivateTextMessage(update);
        if (inboundMessage == null) {
            log.debug("忽略不受支持的 Telegram update: updateId={}", update != null ? update.updateId() : null);
            return;
        }
        ReplyEnvelope replyEnvelope = directMessageService.handle(new DirectMessageIngressCommand(
                "telegram",
                inboundMessage.externalUserId(),
                inboundMessage.externalConversationId(),
                inboundMessage.externalMessageId(),
                inboundMessage.body()
        ));
        try {
            telegramOutboundClient.sendMessage(new TelegramOutboundMessage(inboundMessage.chatId(), replyEnvelope.body()));
        } catch (RuntimeException exception) {
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
