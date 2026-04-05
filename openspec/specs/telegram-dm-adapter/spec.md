# telegram-dm-adapter Specification

## Purpose
定义 Telegram Bot API 私聊文本渠道的接入边界，负责完成 webhook 鉴权、
Telegram update 到统一单聊消息模型的协议翻译、最终文本回复回传和已知私聊目标的主动文本发送，
但不负责核心 Agent 推理、跨平台抽象扩展和非文本交互能力。
## Requirements
### Requirement: System accepts Telegram private text updates
The system SHALL accept Telegram Bot API updates for private-chat text messages and translate them into the existing direct-message application flow.

#### Scenario: Telegram private text update reaches the agent
- **WHEN** a Telegram webhook request contains a `message` from a private chat and the message has text content
- **THEN** the system maps `from.id` to the external user identifier, `chat.id` to the external conversation identifier, `update_id` to the external message identifier, and forwards the normalized message to the direct-message service

### Requirement: System authenticates Telegram webhook requests
The system SHALL verify Telegram webhook authenticity before processing an update.

#### Scenario: Request with valid Telegram webhook secret is processed
- **WHEN** a Telegram webhook request presents the configured secret token
- **THEN** the system continues parsing and processing the update

#### Scenario: Request with missing or invalid secret is rejected
- **WHEN** a Telegram webhook request omits the configured secret token or presents a different value
- **THEN** the system does not invoke the direct-message service for that request

### Requirement: System sends final replies back to Telegram private chats
The system SHALL deliver the final user-visible reply body to the originating Telegram private chat through the Telegram Bot API.

#### Scenario: Agent reply is sent to the source chat
- **WHEN** the direct-message service returns a `ReplyEnvelope` for a Telegram private text update
- **THEN** the system calls Telegram `sendMessage` for the source `chat.id` and sends the `ReplyEnvelope.body` as one final text message

### Requirement: System ignores unsupported Telegram updates safely
The system SHALL acknowledge unsupported Telegram updates without invoking the agent.

#### Scenario: Non-private or non-text Telegram update is ignored
- **WHEN** a Telegram webhook request contains an update that is not a private-chat text message
- **THEN** the system acknowledges the update without invoking the direct-message service or sending a reply

### Requirement: System sends proactive Telegram private text messages
The system SHALL allow the Telegram adapter to send a plain-text message to a known Telegram private chat when an internal conversation has already been resolved to a Telegram outbound target.

#### Scenario: Scheduler-triggered reminder is sent to Telegram chat
- **WHEN** an asynchronous subsystem requests delivery for an internal conversation bound to Telegram and provides a final plain-text body
- **THEN** the Telegram adapter calls Telegram `sendMessage` for the bound private `chat.id` and sends the provided body as one plain-text message

#### Scenario: Telegram proactive send failure is surfaced safely
- **WHEN** Telegram rejects a proactive plain-text send request or the outbound call fails
- **THEN** the Telegram adapter reports a delivery failure to the caller instead of pretending the reminder was delivered
