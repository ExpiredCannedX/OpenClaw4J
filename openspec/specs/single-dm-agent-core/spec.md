# single-dm-agent-core Specification

## Purpose
定义单聊场景下统一的 Agent 核心执行入口，负责基于 workspace 和活跃
会话组装上下文、调用模型并生成最终一次性结构化回复，但不负责渠道协
议适配、Skill 自动匹配或 Tool 调用编排。
## Requirements
### Requirement: System exposes a unified agent entrypoint
The system SHALL provide a single internal agent entrypoint that accepts a normalized direct-message request and returns a structured reply.

#### Scenario: Agent entrypoint returns reply envelope
- **WHEN** the unified agent entrypoint receives a normalized direct-message request
- **THEN** it returns a `ReplyEnvelope` containing a user-visible body and a signals collection

### Requirement: System assembles context from workspace and active conversation
The system SHALL assemble model context from workspace content and recent turns of the active conversation before invoking the language model.

#### Scenario: Recent turns are included in context
- **WHEN** the active conversation already contains prior turns
- **THEN** the system includes the recent conversation history together with workspace context when invoking the model

### Requirement: System returns final one-shot replies only
The system SHALL produce a final one-shot reply for each request and SHALL NOT emit streaming tokens or intermediate progress events in this change.

#### Scenario: Response is returned as one final payload
- **WHEN** the language model invocation completes successfully
- **THEN** the system returns one final `ReplyEnvelope` to the channel layer

### Requirement: System provides a fallback reply on model failure
The system SHALL return a safe fallback reply when model invocation fails or times out.

#### Scenario: Model failure triggers fallback
- **WHEN** the language model call throws an exception or exceeds the configured timeout
- **THEN** the system returns a fallback `ReplyEnvelope` instead of propagating the raw failure to the caller
