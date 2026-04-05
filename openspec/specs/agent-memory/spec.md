# agent-memory Specification

## Purpose
Defines the V1 local memory foundation for a single-user workspace, including layered Markdown fact files, a local SQLite keyword index, provenance-aware writes, and the built-in `memory.search` and `memory.remember` tools.
## Requirements
### Requirement: System stores memories in layered workspace files
The system SHALL persist V1 memory records in three workspace-backed targets: `USER.md` for single-user stable profile entries, `MEMORY.md` for long-term non-profile entries, and `memory/YYYY-MM-DD.md` for append-only session log entries.

#### Scenario: User profile memory is written to USER.md
- **WHEN** `memory.remember` is invoked with target `user_profile` and an allowed profile category
- **THEN** the system persists the memory entry under `USER.md` in the matching profile section

#### Scenario: Long-term memory is written to MEMORY.md
- **WHEN** `memory.remember` is invoked with target `long_term`
- **THEN** the system persists the memory entry to `MEMORY.md`

#### Scenario: Session memory is written to a daily log file
- **WHEN** `memory.remember` is invoked with target `session_log`
- **THEN** the system appends the memory entry to `memory/YYYY-MM-DD.md` using the server local date for that request

#### Scenario: Missing target file is created on demand
- **WHEN** `memory.remember` writes to a target file or the `memory/` directory that does not yet exist
- **THEN** the system creates the required file or directory before persisting the entry

### Requirement: System restricts automatic writes to USER.md
The system SHALL only allow `USER.md` writes for whitelisted stable profile categories and SHALL reject unsupported categories instead of silently downgrading them to another file.

#### Scenario: Allowed profile category is accepted
- **WHEN** `memory.remember` targets `user_profile` with category `preferred_name`, `preference`, `habit`, `taboo`, or `constraint`
- **THEN** the system accepts the write and stores it under the matching `USER.md` section

#### Scenario: Unsupported profile category is rejected
- **WHEN** `memory.remember` targets `user_profile` with a category outside the whitelist
- **THEN** the system returns a structured invalid-arguments error and does not persist the entry

### Requirement: System records provenance for persisted memories
The system SHALL persist provenance with each written memory entry, including write timestamp, channel, trigger reason, and current conversation context when available, while deriving runtime identifiers from the current request context rather than model-supplied arguments.

#### Scenario: Persisted memory entry includes provenance
- **WHEN** `memory.remember` successfully persists a memory entry
- **THEN** the stored entry includes its write timestamp, channel, trigger reason, and optional confidence

#### Scenario: Session-scoped write captures current conversation context
- **WHEN** `memory.remember` persists a `session_log` entry during an agent request
- **THEN** the stored entry includes the current internal conversation identifier or an equivalent conversation-scoped provenance field

#### Scenario: Runtime provenance is not accepted from model arguments
- **WHEN** `memory.remember` is invoked with model-provided values that attempt to override channel or conversation provenance
- **THEN** the system ignores those values and uses the current execution context instead

### Requirement: System maintains a local SQLite index for memory files
The system SHALL maintain a local SQLite single-file index derived from `USER.md`, `MEMORY.md`, and `memory/*.md`, and SHALL support keyword retrieval over indexed memory chunks using an explicit FTS5 tokenizer strategy that is stable across rebuilds.

#### Scenario: Startup refreshes changed memory files
- **WHEN** the service starts and one or more memory files are new or have changed since the last successful index build
- **THEN** the system rebuilds index entries for the affected files before serving memory search requests

#### Scenario: Remember refreshes the affected file index before success
- **WHEN** `memory.remember` successfully writes to a memory file
- **THEN** the system refreshes the SQLite index entries for that file before returning a success result

#### Scenario: Missing memory files do not fail index initialization
- **WHEN** one or more optional memory files or the `memory/` directory are absent during index initialization
- **THEN** the system skips the missing inputs and continues initializing the local index

#### Scenario: FTS tokenizer strategy is recreated deterministically
- **WHEN** the configured FTS5 tokenizer or search schema version differs from the existing local SQLite index
- **THEN** the system rebuilds the derived search index with the configured tokenizer before serving `memory.search`

### Requirement: System provides a memory.search tool
The system SHALL provide a synchronous built-in tool named `memory.search` that performs keyword retrieval against the local SQLite index, uses FTS5 `MATCH` as the primary retrieval path for matchable query terms, preserves scope filtering, and returns structured matches instead of raw file contents.

#### Scenario: Search returns structured matches ordered by FTS relevance
- **WHEN** `memory.search` is invoked with a non-empty query containing one or more terms supported by the configured FTS tokenizer and indexed memory matches those terms
- **THEN** the system returns a structured success result containing matches with relative file path, line range, preview snippet, target bucket, and non-constant score metadata ordered by relevance

#### Scenario: Session scope limits search to the current conversation context
- **WHEN** `memory.search` is invoked with scope `session`
- **THEN** the system only returns matches from session log entries associated with the current conversation context

#### Scenario: Short query terms remain searchable
- **WHEN** `memory.search` is invoked with a non-empty query that contains terms too short to be matched by the configured FTS tokenizer
- **THEN** the system applies deterministic substring fallback for those terms without dropping the active scope filters

#### Scenario: Search with no matches returns an empty result
- **WHEN** `memory.search` is invoked with a query that matches no indexed memory chunk
- **THEN** the system returns a structured success result with an empty matches list

### Requirement: System provides a memory.remember tool
The system SHALL provide a synchronous built-in tool named `memory.remember` that validates target-specific inputs, persists memory to the correct file, and returns structured write metadata.

#### Scenario: Remember returns write location metadata
- **WHEN** `memory.remember` successfully persists a memory entry
- **THEN** the system returns a structured success result containing the target bucket, relative file path, and persisted category when applicable

#### Scenario: Invalid remember input is rejected
- **WHEN** `memory.remember` is invoked with missing content, an unsupported target, or an invalid profile category
- **THEN** the system returns a structured invalid-arguments error

