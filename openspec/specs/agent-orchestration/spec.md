# agent-orchestration Specification

## Purpose
定义单次请求内的有界智能体编排基础，使系统能够在保持最终一次性回复契约不变的前提下，在当前请求生命周期内执行受 step budget 约束的 `Think -> Act -> Observe` 多步同步工具闭环，并复用既有工具安全、确认与失败收敛边界；不包含 sub-agent、异步工具协议或跨请求执行状态持久化。

## Requirements
### Requirement: System executes a bounded orchestration loop within a single request
The system SHALL support a bounded request-local orchestration loop that alternates between model planning and synchronous tool execution until the model returns a final reply or the loop reaches a terminal condition.

#### Scenario: Multi-step request executes more than one allowed tool
- **WHEN** the current request needs one allowed tool result and then a second allowed tool result before the system can answer
- **THEN** the system may execute those tools sequentially within the same request and still return one final `ReplyEnvelope`

### Requirement: System carries forward ordered tool observations between orchestration steps
The system SHALL retain structured observations produced earlier in the current request and SHALL provide them in order to each subsequent planning step and to the terminal final-reply step.

#### Scenario: Second planning step sees the first tool observation
- **WHEN** step one produces a structured success or error observation
- **THEN** step two planning context includes that prior observation in the request-local ordered observation history

### Requirement: System enforces a terminal boundary for budget exhaustion and guarded pauses
The system SHALL stop issuing further planning or tool-execution steps when the request reaches the configured step budget or when the current step yields a terminal guarded observation that requires user confirmation, and it SHALL still converge to one final one-shot reply for the current request.

#### Scenario: Step budget exhaustion stops further tool execution
- **WHEN** the request has consumed the maximum allowed orchestration steps without a final-reply decision
- **THEN** the system stops the loop and produces one final one-shot reply using the accumulated request observations or a safe fallback path

#### Scenario: Confirmation-required observation pauses the current loop
- **WHEN** a step produces a `confirmation_required` observation for a guarded tool request
- **THEN** the system does not execute additional tool steps in the same request and returns one final one-shot reply for the current turn
