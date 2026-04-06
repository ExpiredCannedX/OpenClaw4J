# single-dm-agent-core Specification

## Purpose
定义单聊场景下统一的 Agent 核心执行入口，负责基于 workspace、已解析的 Skill、活跃会话、可用工具目录和当前请求内的有序结构化观察组装上下文，在单次请求内执行受 step budget 约束的同步工具编排闭环并生成最终一次性结构化回复，但不负责渠道协议适配、MCP server 生命周期管理或异步工具编排。
## Requirements
### Requirement: System exposes a unified agent entrypoint
The system SHALL provide a single internal agent entrypoint that accepts a normalized direct-message request and returns a structured reply.

#### Scenario: Agent entrypoint returns reply envelope
- **WHEN** the unified agent entrypoint receives a normalized direct-message request
- **THEN** it returns a `ReplyEnvelope` containing a user-visible body and a signals collection

### Requirement: System assembles context from workspace and active conversation
The system SHALL assemble model context from workspace content, the selected Skill if one is resolved, recent turns of the active conversation, the catalog of available tools, and any ordered structured observations produced earlier in the current request before each planning step. When the request terminates after one or more tool executions or policy results, the system SHALL include the accumulated structured observations in the follow-up model context used to generate the final reply. The available tool catalog MAY include both local tools and discovered MCP tools from ready servers.

#### Scenario: Later planning step includes prior observations and available tools
- **WHEN** the current request has already produced one or more structured tool or policy observations and one or more local or MCP tools are registered
- **THEN** the system includes the selected Skill together with workspace context, recent conversation history, available tool descriptions/schema, and prior observations when asking the model to decide the next action for the next step

#### Scenario: Accumulated observations are included before final reply generation
- **WHEN** the current request terminates after one or more tool executions or policy observations
- **THEN** the system includes the accumulated structured observations in the follow-up model context used to generate the final reply

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

### Requirement: System supports a bounded synchronous orchestration loop per request
The system SHALL allow each request to do exactly one of the following before producing the final one-shot reply: return a final reply immediately, execute a bounded sequence of registered synchronous tool calls that are allowed by policy, or resume at most one previously confirmed pending tool request for the same conversation and continue within the same bounded sequence before returning the final reply.

#### Scenario: Model returns final reply without tool use
- **WHEN** the model decision indicates that no tool call is needed
- **THEN** the system returns one final `ReplyEnvelope` without executing any tool

#### Scenario: Model requests multiple allowed local or MCP tools within budget
- **WHEN** successive planning steps request registered local or MCP-backed tools for the same request and each requested tool is allowed by the safety policy
- **THEN** the system executes those tools in sequence within the bounded loop, feeds each structured result back into later planning, and returns one final `ReplyEnvelope`

#### Scenario: Model requests a guarded tool without confirmation
- **WHEN** the model decision requests a guarded tool for the current request and the safety policy requires confirmation
- **THEN** the system does not execute the real tool, records a structured observation for the blocked request, and still returns one final `ReplyEnvelope`

#### Scenario: Explicit confirmation resumes one pending tool request
- **WHEN** the current request is an explicit confirmation that matches one active pending tool request for the same conversation
- **THEN** the system resumes and executes that stored tool request at most once and MAY continue bounded planning before returning one final `ReplyEnvelope`

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
