# single-dm-channel Specification

## Purpose
定义单聊消息的渠道接入边界，负责接收外部消息、完成标准化、身份映射、
活跃会话复用、渠道回发目标绑定与幂等处理，并把请求转交给统一 Agent 入口，但不负责具体
模型推理与上下文组装。
## Requirements
### Requirement: System accepts normalized direct-message ingress
The system SHALL provide both a development-facing direct-message ingress and one or more real channel adapters that accept single-user messages and normalize them into the internal message model before invoking the agent.

#### Scenario: Direct message reaches the agent through development ingress
- **WHEN** a caller submits a valid direct-message request to the development ingress
- **THEN** the system normalizes the request into the internal message model and forwards it to the unified agent entrypoint

#### Scenario: Direct message reaches the agent through a real adapter
- **WHEN** a supported real channel adapter receives a valid direct-message event from an external platform
- **THEN** the system normalizes the platform payload into the internal message model and forwards it to the unified agent entrypoint

### Requirement: System maintains external-to-internal identity mappings
The system SHALL map channel-native user identifiers and conversation identifiers to platform-agnostic internal identifiers.

#### Scenario: First message creates mappings
- **WHEN** the system receives a direct message from a previously unseen external user in a channel
- **THEN** the system creates an internal user identity, creates an active internal conversation, and stores the external-to-internal mapping

#### Scenario: Existing message sender reuses active conversation
- **WHEN** the system receives another direct message from the same external user in the same channel
- **THEN** the system reuses the existing active internal conversation instead of creating a new one

### Requirement: System handles duplicate direct-message delivery idempotently
The system SHALL detect duplicate delivery of the same external message and avoid invoking the agent multiple times for the same message.

#### Scenario: Duplicate message delivery is ignored
- **WHEN** the same external message identifier is delivered more than once
- **THEN** the system processes it at most once and returns the previously computed result or a duplicate-safe response

### Requirement: System records delivery targets for active internal conversations
The system SHALL maintain a delivery-target binding for each active single-direct-message internal conversation so asynchronous features can route outbound text back to the correct channel conversation without an incoming webhook request.

#### Scenario: Inbound message refreshes conversation delivery binding
- **WHEN** the system accepts a valid direct message, resolves or creates its internal conversation, and finishes normalization
- **THEN** the system stores or refreshes the binding between that internal conversation identifier and the channel-specific outbound conversation target

### Requirement: System resolves outbound targets by internal conversation identifier
The system SHALL expose a platform-agnostic lookup that resolves the current channel delivery target for a known internal conversation identifier.

#### Scenario: Existing internal conversation resolves to an outbound target
- **WHEN** an asynchronous subsystem requests the delivery target for an internal conversation that already has a stored binding
- **THEN** the system returns the bound channel identifier together with the channel-specific outbound conversation target required for delivery
