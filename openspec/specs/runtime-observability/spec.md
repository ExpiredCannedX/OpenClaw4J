# runtime-observability Specification

## Purpose
定义统一单聊主链路的运行期可观测性能力，提供默认开启、可配置关闭的结构化运行事件、单次处理级 trace 上下文和首个面向开发者的控制台观测面，以支持 Telegram 等渠道联调与后续 Web UI 扩展，但不包含用户可见调试信息、事件持久化存储或 JVM 级底层追踪。
## Requirements
### Requirement: System supports configurable runtime observation modes
The system SHALL provide runtime observation modes that can be configured through application configuration for developer-facing tracing of eligible runs and SHALL default to a summary timeline mode.

#### Scenario: Observation defaults to timeline mode
- **WHEN** the application starts without overriding the runtime observation mode
- **THEN** the system uses the summary timeline mode as the default developer-facing observation mode

#### Scenario: Observation can be disabled through configuration
- **WHEN** the runtime observation mode is set to `OFF` in application configuration
- **THEN** the system does not emit developer-visible runtime observation output for normal request processing

#### Scenario: Observation mode can be changed through application configuration
- **WHEN** the runtime observation mode is set to a non-`OFF` mode in application configuration
- **THEN** the system emits developer-visible runtime observation events for eligible runs without changing the user-visible reply contract

### Requirement: System assigns a run-scoped trace identity to observed runs
The system SHALL assign a run-scoped trace identity to each observed direct-message run and SHALL attach that identity to all runtime observation events emitted for the same run.

#### Scenario: One Telegram message produces one correlated event stream
- **WHEN** a single Telegram private text update is accepted and processed through the unified direct-message flow
- **THEN** all emitted observation events for that processing flow share the same run-scoped trace identity

### Requirement: System emits structured lifecycle events at stable execution boundaries
The system SHALL emit structured lifecycle events at stable execution boundaries for ingress handling, agent execution phases, per-step model planning, synchronous tool execution, orchestration termination, final reply generation, reply dispatch, MCP server initialization, MCP tool discovery, and failures. When a run enters the orchestration loop, those events SHALL carry step-related metadata such as the current step index and terminal reason when applicable.

#### Scenario: Multi-step agent run emits step-scoped lifecycle events
- **WHEN** an eligible run enters the agent flow, executes more than one synchronous tool step, and returns a final reply
- **THEN** the emitted observation stream includes structured events for each planning/tool step and the terminal final-reply generation for the same run-scoped trace identity

#### Scenario: Optional MCP server startup failure emits degradation event
- **WHEN** an optional MCP server fails during startup initialization or tool discovery
- **THEN** the system emits a structured observation event that identifies the server alias, the degraded stage, and the downgraded outcome

### Requirement: System keeps runtime observations separate from user-visible reply payloads
The system SHALL keep runtime observation data out of user-visible reply bodies and out of result-level reply signals.

#### Scenario: Observation does not alter reply envelope
- **WHEN** runtime observation is enabled for a request
- **THEN** the returned `ReplyEnvelope` still contains only user-visible body content and result-level signals rather than runtime trace events

### Requirement: System routes observation events through pluggable sinks and provides a console sink
The system SHALL route runtime observation events through a sink abstraction and SHALL provide a developer-visible console sink implementation.

#### Scenario: Console sink prints observation timeline
- **WHEN** the console sink is enabled for an eligible run
- **THEN** the system writes the emitted runtime observation events to the process console in developer-visible form

### Requirement: System limits detailed payload exposure by observation mode
The system SHALL limit large or sensitive payload details by observation mode and SHALL only include detailed previews in the most verbose mode. This limit SHALL also apply to MCP-related command metadata, tool arguments, result previews, and configuration-derived sensitive values.

#### Scenario: Timeline mode uses summary metadata only
- **WHEN** the runtime observation mode is `TIMELINE`
- **THEN** the emitted observation events include summary metadata rather than full prompt, workspace, raw MCP payloads, command environment variables, or complete model output content

#### Scenario: Verbose mode includes truncated previews without secret leakage
- **WHEN** the runtime observation mode is `VERBOSE`
- **THEN** the emitted observation events may include truncated preview fields for debugging without exposing unrestricted raw payloads or sensitive configuration values

### Requirement: System defines runtime observation defaults in application configuration
The system SHALL define the runtime observation configuration defaults in `application.yaml` so the default mode and sink behavior are explicit and centrally managed.

#### Scenario: Application configuration declares observability defaults
- **WHEN** the application loads its default configuration
- **THEN** the runtime observation default mode and sink-related settings are sourced from `application.yaml`
