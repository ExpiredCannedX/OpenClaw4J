# single-dm-agent-core Specification

## Purpose
定义单聊场景下统一的 Agent 核心执行入口，负责基于 workspace、已解
析的 Skill 和活跃会话组装上下文，向模型暴露可用工具目录，并在单次请
求内完成至多一次同步 Tool 调用闭环后生成最终一次性结构化回复，但不
负责渠道协议适配、MCP server 生命周期管理或异步工具编排。
## Requirements
### Requirement: System exposes a unified agent entrypoint
The system SHALL provide a single internal agent entrypoint that accepts a normalized direct-message request and returns a structured reply.

#### Scenario: Agent entrypoint returns reply envelope
- **WHEN** the unified agent entrypoint receives a normalized direct-message request
- **THEN** it returns a `ReplyEnvelope` containing a user-visible body and a signals collection

### Requirement: System assembles context from workspace and active conversation
The system SHALL assemble model context from workspace content, the selected Skill if one is resolved, recent turns of the active conversation, and the catalog of available tools before tool planning. When a tool is executed or a tool request is blocked by the safety policy during the current request, the system SHALL include the structured tool observation in the follow-up model context used to generate the final reply. The available tool catalog MAY include both local tools and discovered MCP tools from ready servers.

#### Scenario: Planning context includes selected skill and available local or MCP tools
- **WHEN** the active conversation already contains prior turns, the current request resolves to a Skill, and one or more local or MCP tools are registered
- **THEN** the system includes the selected Skill together with workspace context, recent conversation history, and available tool descriptions/schema when asking the model to decide the next action

#### Scenario: Blocked or completed tool observation is included before final reply generation
- **WHEN** the system has either executed a tool or produced a structured safety-policy observation during the current request
- **THEN** it includes that structured observation in the follow-up model context used to generate the final reply

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

### Requirement: System emits a skill-applied signal when a skill is selected
The system SHALL include a structured `skill_applied` signal in the returned `ReplyEnvelope` whenever the current request resolves to a Skill before model invocation.

#### Scenario: Explicit skill selection produces signal
- **WHEN** the current request explicitly selects a Skill and the agent completes successfully
- **THEN** the returned `ReplyEnvelope` includes a `skill_applied` signal whose payload identifies the selected skill and marks the activation mode as explicit

#### Scenario: Automatic skill selection produces signal
- **WHEN** the current request automatically matches a Skill and the agent completes successfully
- **THEN** the returned `ReplyEnvelope` includes a `skill_applied` signal whose payload identifies the selected skill and marks the activation mode as automatic

### Requirement: System supports at most one synchronous tool call per request
The system SHALL allow each request to do exactly one of the following before producing the final one-shot reply: return a final reply immediately, execute at most one registered synchronous tool call that is allowed by policy, or resume at most one previously confirmed pending tool request for the same conversation.

#### Scenario: Model returns final reply without tool use
- **WHEN** the model decision indicates that no tool call is needed
- **THEN** the system returns one final `ReplyEnvelope` without executing any tool

#### Scenario: Model requests one allowed local or MCP tool
- **WHEN** the model decision requests one registered local or MCP-backed tool for the current request and the safety policy allows it
- **THEN** the system executes that tool once, feeds its structured result back to the model, and returns one final `ReplyEnvelope`

#### Scenario: Model requests a guarded tool without confirmation
- **WHEN** the model decision requests a guarded tool for the current request and the safety policy requires confirmation
- **THEN** the system does not execute the real tool, records a structured observation for the blocked request, and still returns one final `ReplyEnvelope`

#### Scenario: Explicit confirmation resumes one pending tool request
- **WHEN** the current request is an explicit confirmation that matches one active pending tool request for the same conversation
- **THEN** the system resumes and executes that stored tool request at most once before returning one final `ReplyEnvelope`

### Requirement: System degrades safely when tool invocation cannot complete
The system SHALL convert unavailable tools, invalid tool arguments, safety-policy denials, confirmation-required results, and tool execution failures into structured tool observations or a safe fallback path, and it SHALL NOT expose raw tool failures directly to the channel layer. This requirement applies equally to local tools and MCP-backed tools.

#### Scenario: Requested tool is unavailable
- **WHEN** the model requests a tool name that is not present in the registry
- **THEN** the system records a structured tool error observation for that request and preserves one final `ReplyEnvelope` outcome

#### Scenario: Tool request is denied by safety policy
- **WHEN** a registered local or MCP-backed tool request is rejected by the safety policy before execution
- **THEN** the system converts that policy result into a structured tool observation or safe final reply instead of propagating a raw failure

#### Scenario: MCP tool invocation cannot complete
- **WHEN** a registered MCP-backed tool cannot complete because the remote server disconnects, times out, or returns a transport-level failure during synchronous execution
- **THEN** the system converts the failure into a structured tool error observation or safe fallback reply instead of propagating the raw failure to the caller
