# skill-resolution Specification

## Purpose
定义本地 Skill 元数据解析、显式优先选择与保守自动匹配能力，为 Agent Core 提供可解释、低膨胀的单 Skill 解析边界，但不包含语义召回、会话内去重或渐进式执行期资源加载。
## Requirements
### Requirement: System parses local skill metadata from SKILL.md front matter
The system SHALL parse local `SKILL.md` files into normalized Skill definitions by reading YAML front matter metadata and treating the remaining Markdown body as the skill instruction content.

#### Scenario: Skill front matter contains recognized metadata
- **WHEN** a local `SKILL.md` file contains YAML front matter with `name`, `description`, or `keywords`
- **THEN** the system extracts those recognized fields into the Skill definition and preserves the Markdown body as the skill content

#### Scenario: Skill front matter contains unknown metadata
- **WHEN** a local `SKILL.md` file contains front matter keys outside the recognized Skill fields
- **THEN** the system ignores the unknown keys instead of failing the request

### Requirement: System prioritizes explicit skill selection
The system SHALL prefer a user-explicit Skill request over any automatic Skill matching result.

#### Scenario: Explicitly named skill is selected
- **WHEN** the current user message contains `$skill-name` and that local Skill exists in the workspace
- **THEN** the system selects that Skill directly and skips automatic matching for the request

#### Scenario: Markdown link label explicitly names a skill
- **WHEN** the current user message contains a Markdown link whose label is `$skill-name` and that local Skill exists in the workspace
- **THEN** the system selects that Skill directly and skips automatic matching for the request

#### Scenario: Explicitly named skill is unavailable
- **WHEN** the current user message explicitly names a Skill that does not exist in the workspace
- **THEN** the system continues the request without selecting a Skill and without failing the request

### Requirement: System conservatively auto-selects at most one skill
The system SHALL perform conservative automatic Skill matching from front matter keyword metadata only when no explicit Skill has been selected, and it SHALL resolve at most one Skill for a request. Automatic matching SHALL be case-insensitive and SHALL treat hyphen, underscore, and space separators as equivalent.

#### Scenario: Single skill matches automatically
- **WHEN** no explicit Skill is requested and exactly one local Skill matches the current user message by its front matter keywords
- **THEN** the system selects that one Skill for the request

#### Scenario: Keyword match succeeds after lightweight normalization
- **WHEN** no explicit Skill is requested and a local Skill keyword matches the current user message after case folding and hyphen/underscore/space normalization
- **THEN** the system treats that keyword as a valid automatic match

#### Scenario: Automatic matching is ambiguous
- **WHEN** no explicit Skill is requested and multiple local Skills tie for the strongest automatic match
- **THEN** the system selects no Skill for the request
