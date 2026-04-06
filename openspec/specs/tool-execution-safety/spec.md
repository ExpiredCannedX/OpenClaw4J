# tool-execution-safety Specification

## Purpose
定义工具调用的服务端安全治理基础能力，覆盖统一安全策略判定、显式确认状态持久化、filesystem 写操作参数级校验、结构化审计日志，以及把工具输出与检索内容视为不可信输入的最小防护边界，但不包含更细粒度的多租户策略域、dry-run 预览或复杂人工审批流。

## Requirements
### Requirement: System evaluates every tool call through a server-side safety policy
The system SHALL evaluate every local or MCP-backed tool call through a server-side safety policy before any real tool execution occurs. The policy SHALL derive its decision from server-side tool safety metadata, normalized tool arguments, runtime execution context, and persisted confirmation state, and it SHALL classify each request as `allowed`, `denied`, or `confirmation_required`.

#### Scenario: Read-only tool is allowed immediately
- **WHEN** a tool is registered with a safety profile that marks it as read-only and the request arguments pass all configured validators
- **THEN** the system classifies the request as `allowed` and permits the real tool execution to continue

#### Scenario: High-risk tool requires confirmation before execution
- **WHEN** a tool is registered with a safety profile that requires explicit confirmation and the current request has not matched a valid confirmed state
- **THEN** the system classifies the request as `confirmation_required` and does not execute the real tool

### Requirement: System persists explicit confirmation state for guarded tool calls
The system SHALL persist a pending confirmation record for each tool request that requires explicit confirmation. Each record SHALL bind the internal conversation, internal user, tool name, normalized argument fingerprint, risk summary, status, and expiry time so a later confirmation can be validated entirely on the server side.

#### Scenario: Pending confirmation record is created
- **WHEN** a high-risk tool request is classified as `confirmation_required`
- **THEN** the system stores a pending confirmation record for the same conversation and user instead of executing the tool

#### Scenario: Explicit confirmation unlocks the stored tool request
- **WHEN** the same conversation later sends an explicit confirmation message before the pending record expires
- **THEN** the system marks that pending record as confirmed and allows only the matching stored tool request to proceed

#### Scenario: Expired or mismatched confirmation is rejected
- **WHEN** a confirmation message arrives after expiry or from a different conversation or user than the stored pending record
- **THEN** the system rejects the confirmation and keeps the protected tool request from executing

### Requirement: System validates filesystem write requests against workspace policy
The system SHALL validate filesystem write, delete, move, rename, recursive, and batch modification requests before transport invocation. Validation SHALL include path normalization, workspace-root confinement, symbol-link escape checks, sensitive-path denylist enforcement, and rejection of recursive or batch operations that are outside the approved P0 boundary.

#### Scenario: Path escape is denied before tool transport
- **WHEN** a filesystem write-capable tool request targets a path that resolves outside the configured workspace root
- **THEN** the system rejects the request before invoking the underlying tool transport

#### Scenario: Sensitive workspace file is denied
- **WHEN** a filesystem write-capable tool request targets a denylisted file such as `SOUL.md`, `SKILLS.md`, or `AGENTS.md`
- **THEN** the system rejects the request even if the path is still inside the workspace root

#### Scenario: Safe in-workspace write passes validation
- **WHEN** a filesystem write-capable tool request targets a non-sensitive path inside the workspace root and does not use blocked recursive or batch semantics
- **THEN** the system allows the request to continue to later policy or execution stages

### Requirement: System records a structured audit trail for guarded tool calls
The system SHALL record a structured audit log entry for each guarded high-risk tool request. Each entry SHALL include at least the requested tool name, normalized argument fingerprint, policy decision, confirmation status, final execution outcome, and timestamps needed for later review.

#### Scenario: Denied tool call is audited
- **WHEN** the safety policy denies a guarded tool request
- **THEN** the system stores an audit record that captures the denial decision and the reason category

#### Scenario: Confirmed tool execution is audited end-to-end
- **WHEN** a guarded tool request is later confirmed and then executes
- **THEN** the system stores audit information that links the original pending request, the confirmation transition, and the final execution outcome

### Requirement: System treats retrieved content as untrusted for safety decisions
The system SHALL treat tool outputs, fetched web content, workspace file content, and memory content as untrusted input for tool-safety decisions. Such content SHALL NOT create confirmation state, override denylist rules, or bypass a server-side deny decision.

#### Scenario: Untrusted content cannot self-authorize a dangerous tool call
- **WHEN** retrieved content instructs the agent to ignore safety rules or claims that execution is already approved
- **THEN** the system still requires a matching server-side allow decision and valid confirmation state before executing a guarded tool
