# tool-system Specification

## Purpose
定义统一的 Tool System 基础能力，用标准化工具定义、唯一名称注册、同步执行结果归一化、首个内置 `time` 工具，以及本地工具与 MCP 工具的统一目录与调用边界，为 Agent Core 提供独立于具体模型 SDK 的工具目录，但不包含异步工具编排或 memory/reminder 等业务工具实现。
## Requirements
### Requirement: System exposes normalized tool metadata for model consumption
The system SHALL represent each available tool through a normalized definition that includes a unique tool name, a human-readable description, and a machine-consumable input schema expressed as a general JSON Schema document so Agent Core can expose a unified tool catalog to the model without depending on provider-specific SDK types or losing MCP schema details.

#### Scenario: Tool catalog exposes normalized definitions from local and MCP sources
- **WHEN** Agent Core queries the registered tool catalog for the current request
- **THEN** each available local or MCP-backed tool is returned with its normalized name, description, and input schema

### Requirement: System registers and resolves tools by unique name
The system SHALL provide a registry that lists registered tools from all enabled tool sources and resolves a tool by its exact unique name across the combined catalog.

#### Scenario: Registered local or MCP-backed tool can be resolved by name
- **WHEN** a local tool named `time` and an MCP-backed tool named `mcp.filesystem.read_file` are both present in the combined tool registry
- **THEN** the registry returns the matching tool for each exact unique name request

#### Scenario: Duplicate tool names are rejected across the combined catalog
- **WHEN** two tool implementations from any source attempt to expose the same internal unique tool name
- **THEN** the system rejects the duplicate registration instead of exposing an ambiguous tool catalog

### Requirement: System executes synchronous tools with structured outcomes
The system SHALL execute registered local and MCP-backed tools synchronously and return a structured outcome that is either a success result or an error result, rather than propagating raw tool exceptions or raw MCP client failures to callers.

#### Scenario: Successful tool execution returns structured success
- **WHEN** a registered local or MCP-backed tool completes successfully
- **THEN** the executor returns a structured success outcome containing the tool name and normalized result payload

#### Scenario: Failed tool execution returns structured error
- **WHEN** a registered local or MCP-backed tool throws, reports invalid arguments, times out, or cannot complete due to MCP transport failure
- **THEN** the executor returns a structured error outcome containing the tool name and error details

### Requirement: System provides a built-in time tool
The system SHALL provide a built-in synchronous tool named `time` that requires no mandatory input arguments and returns the current local server time in both machine-readable and human-readable form.

#### Scenario: Time tool returns current timestamp and timezone
- **WHEN** Agent Core invokes the built-in `time` tool
- **THEN** the tool returns a structured success result containing a current timestamp and timezone information
