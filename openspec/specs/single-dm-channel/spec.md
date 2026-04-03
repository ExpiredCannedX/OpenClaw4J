# single-dm-channel Specification

## Purpose
TBD - created by archiving change add-single-dm-core. Update Purpose after archive.
## Requirements
### Requirement: System accepts normalized direct-message ingress
The system SHALL provide one development-facing direct-message ingress that accepts single-user messages and normalizes them into the internal message model before invoking the agent.

#### Scenario: Direct message reaches the agent
- **WHEN** a caller submits a valid direct-message request to the development ingress
- **THEN** the system normalizes the request into the internal message model and forwards it to the unified agent entrypoint

### Requirement: System maintains external-to-internal identity mappings
The system SHALL map channel-native user identifiers and conversation identifiers to platform-agnostic internal identifiers.

#### Scenario: First message creates mappings
- **WHEN** the system receives a direct message from a previously unseen external user in a channel
- **THEN** the system creates an internal user identity, creates an active internal conversation, and stores the external-to-internal mapping

#### Scenario: Existing message sender reuses active conversation
- **WHEN** the system receives another direct message from the same external user in the same channel
- **THEN** the system reuses the existing active internal conversation instead of creating a new one

### Requirement: System handles duplicate direct-message delivery idempotently
The system SHALL detect duplicate delivery of the same external message and avoid invoking the agent multiple times for the same message.

#### Scenario: Duplicate message delivery is ignored
- **WHEN** the same external message identifier is delivered more than once
- **THEN** the system processes it at most once and returns the previously computed result or a duplicate-safe response

