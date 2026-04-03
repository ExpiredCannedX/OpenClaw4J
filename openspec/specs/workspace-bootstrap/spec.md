# workspace-bootstrap Specification

## Purpose
定义 Agent 运行前的基础 workspace 加载能力，负责读取核心规则与记忆
文件并区分静态规则和动态记忆来源，为上下文组装提供稳定输入，但不负
责记忆写回、索引构建或检索召回。
## Requirements
### Requirement: System loads core workspace files for each agent run
The system SHALL load the configured workspace and read the core files required for minimal context assembly: `SOUL.md`, `USER.md`, and `MEMORY.md`.

#### Scenario: Core workspace files are available
- **WHEN** an agent run starts and the configured workspace contains `SOUL.md`, `USER.md`, and `MEMORY.md`
- **THEN** the system reads those files and makes their contents available to the context assembly stage

### Requirement: System tolerates missing optional workspace content
The system SHALL continue the agent run when one or more core workspace files are absent, as long as the workspace root is valid.

#### Scenario: Missing workspace file does not fail the request
- **WHEN** `USER.md` or `MEMORY.md` is missing from the configured workspace
- **THEN** the system treats the missing file as empty context and continues the request without aborting the agent run

### Requirement: System separates static rules from dynamic memory sources
The system SHALL distinguish static rule content from dynamic memory content during workspace loading.

#### Scenario: Static and dynamic files are tagged separately
- **WHEN** the system loads `SOUL.md`, `USER.md`, and `MEMORY.md`
- **THEN** it marks `SOUL.md` as static rule context and marks `USER.md` and `MEMORY.md` as dynamic memory context
