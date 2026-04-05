# scheduler-reminders Specification

## Purpose
定义一次性提醒与后台调度的最小闭环，负责提供同步 `reminder.create` 工具、提醒持久化、
固定 heartbeat 扫描、失败重试与按内部会话回到原渠道发送，但不包含 cron、
自然语言时间解析、提醒列表管理或跨渠道高级调度编排。

## Requirements
### Requirement: System provides a synchronous reminder.create tool
The system SHALL provide a synchronous built-in tool named `reminder.create` that accepts a reminder text and an absolute future timestamp with explicit timezone information, persists a one-time reminder for the current internal conversation, and returns structured confirmation metadata.

#### Scenario: Valid one-time reminder is created
- **WHEN** `reminder.create` is invoked with non-empty text, a future absolute timestamp, and a supported single-direct-message conversation context
- **THEN** the system persists a reminder record linked to the current internal conversation and returns a structured success result containing the reminder identifier, normalized scheduled time, status, and conversation identifier

#### Scenario: Invalid reminder input is rejected
- **WHEN** `reminder.create` is invoked with missing text, a timestamp without explicit timezone information, or a timestamp that is not in the future
- **THEN** the system returns a structured invalid-arguments result and does not persist a reminder record

### Requirement: System persists reminder tasks for restart-safe scheduling
The system SHALL persist reminder tasks and their scheduling state in local durable storage so pending reminders remain triggerable after service restart.

#### Scenario: Pending reminder survives restart
- **WHEN** a reminder is created successfully and the service restarts before its scheduled time
- **THEN** the system reloads the persisted reminder state and continues treating that reminder as pending for future heartbeat scans

### Requirement: System dispatches due reminders through background heartbeat
The system SHALL run a background scheduler heartbeat that scans due one-time reminders, claims each reminder at most once per healthy process run, resolves the current delivery target from the internal conversation identifier, and sends the reminder text back to that conversation's channel.

#### Scenario: Due reminder is delivered to the bound conversation
- **WHEN** a persisted reminder reaches its scheduled time and the internal conversation has a resolvable delivery target
- **THEN** the background scheduler sends the reminder text to that conversation's channel and marks the reminder as delivered

#### Scenario: Missing delivery target prevents dispatch
- **WHEN** a due reminder is scanned but the system cannot resolve a delivery target for its internal conversation identifier
- **THEN** the system records the dispatch failure in reminder state and does not mark the reminder as delivered

### Requirement: System retries failed reminder deliveries with bounded attempts
The system SHALL retry failed reminder deliveries with a bounded retry budget and SHALL mark the reminder as failed after the retry budget is exhausted.

#### Scenario: Transient delivery failure is rescheduled
- **WHEN** reminder dispatch fails before delivery is confirmed and the reminder has remaining retry attempts
- **THEN** the system increments the attempt count, schedules the next attempt time, and returns the reminder to a pending schedulable state

#### Scenario: Retry budget exhaustion marks reminder failed
- **WHEN** reminder dispatch fails and the reminder has no retry attempts remaining
- **THEN** the system marks the reminder as failed and excludes it from further automatic dispatch attempts
