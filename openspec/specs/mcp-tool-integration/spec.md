# mcp-tool-integration Specification

## Purpose
定义基于 `stdio` MCP server 的外部工具接入能力，覆盖配置声明、启动初始化、工具发现、稳定命名、同步调用与失败降级，并将发现到的 MCP 工具纳入统一 Tool System 与 Agent Core 的一次工具调用闭环，但不包含 Resource、Prompt、Sampling、异步工具协议或运行期热刷新。

## Requirements
### Requirement: System supports configured stdio MCP servers with per-server startup policy
The system SHALL allow application configuration to declare zero or more MCP servers, each with a unique server alias, `stdio` launch command, optional launch arguments, optional environment/work directory settings, and a `required` startup policy flag. The system SHALL initialize each configured server during application startup.

#### Scenario: Optional MCP server starts successfully
- **WHEN** an MCP server is configured with `required=false` and its `stdio` process starts and initializes successfully during application startup
- **THEN** the system marks that server as ready for tool discovery and invocation

#### Scenario: Optional MCP server fails and degrades
- **WHEN** an MCP server is configured with `required=false` and its process launch or initialization fails during application startup
- **THEN** the system continues application startup, records the server as unavailable, and excludes that server from MCP tool discovery

#### Scenario: Required MCP server failure blocks startup
- **WHEN** an MCP server is configured with `required=true` and its process launch or initialization fails during application startup
- **THEN** the system fails application startup instead of running without that server

### Requirement: System discovers MCP tools and assigns stable internal names
The system SHALL discover tools from each ready MCP server during startup initialization and SHALL expose every discovered tool under the internal unique name `mcp.<serverAlias>.<toolName>`.

#### Scenario: Ready MCP server contributes prefixed tools
- **WHEN** a ready MCP server with alias `filesystem` advertises a tool named `read_file`
- **THEN** the system exposes that tool to the internal tool catalog as `mcp.filesystem.read_file`

#### Scenario: Unavailable MCP server contributes no tools
- **WHEN** an MCP server is unavailable because startup initialization degraded
- **THEN** the system does not expose any tools for that server in the tool catalog

### Requirement: System preserves MCP tool input schemas as general JSON Schema
The system SHALL preserve each discovered MCP tool input schema as a normalized general JSON Schema document so nested objects, arrays, enum constraints, and required fields remain visible in the tool definition exposed to Agent Core.

#### Scenario: Nested MCP schema is preserved in tool definition
- **WHEN** a discovered MCP tool declares an input schema containing nested object properties, array items, and enum values
- **THEN** the exposed internal tool definition retains those schema structures instead of flattening them into a lossy simplified model

### Requirement: System invokes discovered MCP tools through the unified synchronous tool contract
The system SHALL invoke discovered MCP tools synchronously through the initialized MCP session only after the unified safety policy allows the request, and it SHALL convert allowed, denied, or failed invocation outcomes into the same structured success/error result model used by local tools. Filesystem write-capable MCP tools SHALL also pass argument-level safety validation before any MCP transport call is sent.

#### Scenario: Allowed MCP tool invocation returns structured success
- **WHEN** Agent Core invokes a discovered MCP tool, the unified safety policy allows the request, and the MCP server returns a successful result payload
- **THEN** the system returns a structured tool success outcome containing the internal tool name and normalized payload

#### Scenario: Unsafe filesystem write request is blocked before transport
- **WHEN** Agent Core invokes a discovered filesystem write-capable MCP tool with arguments that violate workspace-root or sensitive-path policy
- **THEN** the system returns a structured tool error outcome without sending the request to the MCP session

#### Scenario: MCP invocation timeout or transport failure returns structured error
- **WHEN** Agent Core invokes a discovered MCP tool, the request has already passed policy checks, and the MCP session times out, disconnects, or otherwise cannot complete the request
- **THEN** the system returns a structured tool error outcome instead of propagating the raw MCP client failure
