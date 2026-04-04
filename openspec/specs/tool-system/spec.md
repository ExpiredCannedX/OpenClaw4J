# tool-system Specification

## Purpose
定义统一的 Tool System 基础能力，用标准化工具定义、唯一名称注册、同步执行结果归一化和首个内置 `time` 工具，为 Agent Core 提供独立于具体模型 SDK 的本地工具目录与调用边界，但不包含 MCP 接入、异步工具编排或 memory/reminder 等业务工具实现。
## Requirements
### Requirement: System exposes normalized tool metadata for model consumption
The system SHALL represent each available tool through a normalized definition that includes a unique tool name, a human-readable description, and a machine-consumable input schema so Agent Core can expose the tool catalog to the model without depending on provider-specific SDK types.

#### Scenario: Tool catalog exposes normalized definitions
- **WHEN** Agent Core queries the registered tool catalog for the current request
- **THEN** each available tool is returned with its normalized name, description, and input schema

### Requirement: System registers and resolves tools by unique name
The system SHALL provide a registry that lists registered tools and resolves a tool by its exact unique name.

#### Scenario: Registered tool can be resolved by name
- **WHEN** a tool named `time` is registered in the local tool registry
- **THEN** the registry returns that tool when asked for the exact name `time`

#### Scenario: Duplicate tool names are rejected
- **WHEN** two tool implementations attempt to register the same tool name
- **THEN** the system rejects the duplicate registration instead of exposing an ambiguous tool catalog

### Requirement: System executes synchronous tools with structured outcomes
The system SHALL execute registered tools synchronously and return a structured outcome that is either a success result or an error result, rather than propagating raw tool exceptions to callers.

#### Scenario: Successful tool execution returns structured success
- **WHEN** a registered tool completes successfully
- **THEN** the executor returns a structured success outcome containing the tool name and normalized result payload

#### Scenario: Failed tool execution returns structured error
- **WHEN** a registered tool throws or reports invalid arguments during execution
- **THEN** the executor returns a structured error outcome containing the tool name and error details

### Requirement: System provides a built-in time tool
The system SHALL provide a built-in synchronous tool named `time` that requires no mandatory input arguments and returns the current local server time in both machine-readable and human-readable form.

#### Scenario: Time tool returns current timestamp and timezone
- **WHEN** Agent Core invokes the built-in `time` tool
- **THEN** the tool returns a structured success result containing a current timestamp and timezone information
