# OpenClaw4J

OpenClaw4J 是一个本地优先、文件驱动、面向单聊场景的 Java Agent 内核。

它把人格、规则、Skill、长期记忆和工具边界沉淀在本地 workspace 中，而不是把状态完全压进模型上下文。当前版本已经具备统一单聊入口、多步同步工具编排、本地记忆检索与写入、一次性提醒调度、Telegram 私聊适配、MCP 工具接入以及服务端工具安全治理能力。

## 项目简介

OpenClaw4J 关注的不是「做一个能调用大模型的聊天接口」，而是「做一个可以持续积累、可解释、可治理、可迁移的本地 Agent 内核」。

在这个项目里，模型负责推理和决策，workspace 负责沉淀人格、规则、技能和记忆。系统通过统一的单聊入口接收消息，在一个受预算约束的同步执行闭环里完成 `Think -> Act -> Observe -> Reply`，并通过本地工具、MCP 工具、记忆检索和提醒调度扩展 Agent 的动作能力。

当前实现聚焦单聊 V1，不追求一次性覆盖群聊、多渠道复杂交互、异步工具协议或开发型工具能力，而是先把最小可运行、可持续演进的 Agent 主链路打稳。

## 为什么是 OpenClaw4J

OpenClaw4J 的设计重点是把 Agent 的长期资产从「瞬时 prompt」迁移到「本地可管理的 workspace」。

这意味着：

- 规则和人格不是隐藏在程序常量里的，而是由本地 Markdown 文件承载。
- Skill 是独立的流程知识，不和 Tool 的动作能力混在一起。
- 长期记忆和会话事实源保存在本地文件与本地 SQLite 中，具备迁移和审计基础。
- Tool 调用不是「模型说了算」，而是经过服务端统一安全策略、确认状态和参数校验之后才能真正执行。
- 真实渠道接入、开发调试入口、提醒调度、MCP 扩展都围绕统一 Agent Core 工作，而不是各自维护一套孤立逻辑。

如果把它概括成一句话：OpenClaw4J 试图把 Agent 从「一个会说话的接口」做成「一个有本地资产和运行边界的系统」。

## 设计目标

- 本地优先：人格、规则、Skill、记忆、索引和调度状态优先保存在本地。
- 文件驱动：核心知识以 Markdown 为主，SQLite 负责索引、检索和结构化持久化。
- 单聊优先：当前只聚焦单聊消息主链路，不提前引入群聊和线程等复杂语义。
- Skill 与 Tool 分离：Skill 描述流程、方法论和 SOP，Tool 提供动作能力。
- 可解释：Skill 选择、工具结果、策略拦截和最终回复边界都应可追踪。
- 可治理：工具调用必须经过服务端安全策略，而不是只依赖 prompt 约束。
- 渠道解耦：Agent Core 不直接依赖具体 IM 平台 payload。
- 可演进：在保持核心边界稳定的前提下，逐步扩展更多渠道、工具和记忆能力。

## 核心架构

```text
Channel / Dev Ingress                | 入口层：开发 HTTP 入口与真实渠道 adapter
        │
        ▼
Single DM Normalization              | 标准化层：统一内部消息模型、身份映射、幂等与会话复用
        │
        ▼
Agent Core                           | 执行核心：组装上下文、驱动模型决策、收敛最终回复
  ├─ Workspace bootstrap             | 加载 workspace 中的规则、记忆与本地 Skill 文档
  ├─ Skill resolution                | 解析并选择显式指定或自动匹配的 Skill
  ├─ Think -> Act -> Observe loop    | 受 step budget 约束的同步规划、工具调用与观察回填
  │  ├─ Local Tools                  | 本地内置工具能力
  │  ├─ MCP Tools                    | 通过 MCP server 暴露的扩展工具能力
  │  ├─ Memory Index                 | 本地记忆索引与检索能力
  │  └─ Reminder Scheduler           | 一次性提醒创建、扫描、触发与回发能力
  └─ Final ReplyEnvelope             | 在完成编排后输出 body + signals 的统一回复协议
```

一次请求的大致流转过程如下：

1. 外部消息通过开发用 HTTP 入口或真实渠道 adapter 进入系统。
2. 渠道层完成消息标准化、外部身份到内部身份的映射、活跃会话复用和幂等处理。
3. Agent Core 加载 workspace、解析可用 Skill、读取最近会话、暴露可用工具目录，并进入受 step budget 约束的同步编排闭环。
4. 如果模型决定调用工具，系统先经过统一安全策略判定，再执行本地工具或 MCP 工具，并把结构化结果回填给后续步骤。
5. 请求最终总是收敛为一个一次性 `ReplyEnvelope`，由渠道层回传给用户。
```text
[应用启动]
OpenClaw4JApplication.main
  |
  +--> ToolRuntimeConfiguration
  |      |- 组装 ToolRegistry(本地工具 + MCP工具)
  |      |- 组装 ToolExecutor(DefaultToolExecutor)
  |      `- 组装 ToolPolicyGuard(策略/确认/审计)
  |
  +--> MemoryStartupInitializer.run
  |      `- 启动时刷新 memory 索引
  |
  `--> 等待入站请求
          |
          +====================== 入站双入口 ======================+
          |                                                      |
          |  A) HTTP 开发入口                                    | B) Telegram Webhook
          |  DirectMessageController.directMessage              | TelegramWebhookController.handleWebhook
          |      |                                              |      |- secret 校验失败 -> 401
          |      |                                              |      `- TelegramWebhookService.handle
          |      |                                              |            |- 仅提取“私聊文本”
          |      |                                              |            `- 转为 DirectMessageIngressCommand
          |      |                                              |
          +---------------------------> DirectMessageService.handleWithMetadata
                                         |- trace 创建/复用
                                         |- 幂等命中(已处理/处理中) -> 复用历史结果
                                         |- 身份映射 externalUser -> internalUser
                                         |- 会话复用/创建 internalConversation
                                         |- 保存会话回发目标
                                         `- 调用 AgentFacade.reply
                                                    |
                                                    v
                                   DefaultAgentFacade.reply (主编排闭环)
                                     |- 加载 WorkspaceSnapshot
                                     |- Skill 解析（可选）
                                     |- 读取 recent turns + 追加用户轮次
                                     |- 处理“显式确认恢复执行”（可选）
                                     |
                                     |- while 未终止:
                                     |    1) decideNextAction(...)
                                     |       `- ChatClientAgentModelClient.decideNextAction
                                     |          -> final_reply / tool_call
                                     |    2) 若 tool_call:
                                     |       DefaultToolExecutor.execute
                                     |         |- ToolPolicyGuard.evaluate
                                     |         |    -> allowed / denied / confirmation_required
                                     |         `- 真正执行工具(本地或MCP)并收敛为 ToolExecutionResult
                                     |    3) 观察结果回填，推进 step budget/终止原因
                                     |
                                     |- resolveReplyBody
                                     |    |- 无工具观察: 复用 planning final_reply
                                     |    `- 有工具观察: generateFinalReply
                                     |
                                     `- buildReplyEnvelope
                                          |- 追加 assistant turn
                                          `- 返回 ReplyEnvelope(body + signals)
                                                    |
                                                    v
                                    +----------------+----------------+
                                    |                                 |
                                    | HTTP 入口: 直接返回 ReplyEnvelope | Telegram 入口: 发送 reply body 到 chatId
                                    |                                 | (重复投递则跳过重复发送)
                                    +---------------------------------+
```
## 当前能力边界

当前仓库已经具备以下能力：

- 统一单聊入口，支持开发用 direct-message HTTP 入口和真实渠道 adapter 接入。
- 平台无关的内部消息模型、外部身份映射、活跃会话复用和消息幂等处理。
- 统一 Agent 入口，输出结构化 `ReplyEnvelope(body + signals)`。
- 受 step budget 约束的 `Think -> Act -> Observe -> Reply` 多步同步工具编排。
- 本地 Skill 解析与选择，支持显式指定和保守自动匹配。
- 本地 Tool System，支持统一定义、JSON Schema 输入描述、结构化成功/失败结果。
- 已归档的内置工具：`time`、`memory.search`、`memory.remember`、`reminder.create`。
- 基于 `stdio` 的 MCP server 接入与工具发现。
- Telegram 私聊文本 webhook 接入与主动文本回发。
- 本地记忆分层存储和 SQLite FTS 检索。
- 一次性提醒调度、失败重试和按会话回发。
- 运行期可观测性与工具安全治理基础能力。

当前回复模型仍然是 one-shot final reply，不支持流式输出、消息编辑或中途进度事件。

## Workspace 模型

Workspace 是 OpenClaw4J 的长期资产容器，也是系统设计里最重要的基础概念。

当前最小核心文件包括：

- `SOUL.md`：静态人格与规则边界。
- `SKILLS.md`：全局技能索引或高层规则。
- `USER.md`：单用户稳定画像。
- `MEMORY.md`：长期非画像记忆。

此外，系统还会发现：

- `skills/**/SKILL.md`：本地 Skill 文档。
- `memory/YYYY-MM-DD.md`：按日期组织的会话日志型记忆。

这套分层的意义在于：

- 静态规则与动态记忆分离。
- Skill 文档与基础规则分离。
- 文件是事实源，SQLite 负责索引和检索，不替代原始知识载体。
- workspace 可以被复制、备份、迁移，从而迁移 Agent 的人格、规则、技能和长期记忆。

```text
====================== Workspace 主链路（启动期） ======================

[应用启动]
OpenClaw4JApplication
   |
   v
[定位 workspace 根目录]
OpenClawProperties.workspaceRoot
   |
   v
[记忆索引预热]
MemoryStartupInitializer.run(...)
   |
   `--> LocalMemoryService.refreshIndexOnStartup(...)
         (扫描 workspace/memory -> 建立/刷新索引)


====================== Workspace 主链路（请求期） ======================

[消息进入 Agent]
DefaultAgentFacade.reply(request)
   |
   v
[加载 workspace 快照]
WorkspaceLoader.load()
   |
   +--> 读取静态规则文件
   |      (如 SOUL.md / USER.md / SKILLS.md / MEMORY.md)
   |
   +--> 读取动态记忆文件
   |      (memory/YYYY-MM-DD.md ...)
   |
   +--> 发现本地 Skill 文档
   |      (skills/**/SKILL.md)
   |
   `--> 组装 WorkspaceSnapshot
         (staticRules + dynamicMemories + localSkillDocuments)
   |
   v
[Skill 解析]
SkillResolver.resolve(message, workspaceSnapshot.localSkillDocuments)
   |
   v
[Prompt 组装注入]
AgentPromptAssembler.appendBaseContext(...)
   |
   +--> 静态规则注入
   +--> 选中 Skill 注入（可选）
   +--> 动态记忆注入
   +--> 最近会话注入
   `--> 当前用户消息注入
   |
   v
[Think -> Act -> Observe 循环]
模型决策 / 工具调用 / 观察回填
   |
   v
[最终回复收敛]
ReplyEnvelope(body + signals)
   |
   v
[会话沉淀]
ConversationTurnRepository.appendTurn(...)
(本轮对话写入，供下次请求继续作为 recent turns)


====================== 与 workspace 相关的工具读写路径 ======================

[tool_call: memory.search / memory.remember / reminder.create ...]
   |
   v
[统一工具执行链路 + 安全策略]
DefaultToolExecutor -> ToolPolicyGuard
   |
   v
[读写 workspace 或其索引]
结果以 ToolExecutionResult 回填到当前编排
```

## Skill 系统

在 OpenClaw4J 中，Skill 不是工具，也不是模型参数，而是对某类任务处理方法的结构化表达。

当前 Skill 系统具备以下边界：

- 从本地 `SKILL.md` 读取 front matter 元数据和正文内容。
- 显式指定 Skill 优先于自动匹配。
- 自动匹配采用保守策略，只在没有显式指定时进行。
- 单次请求最多解析一个 Skill。
- Skill 选中后会以结构化 signal 的形式出现在最终回复结果中。

这种设计的目的，是把「做事方法」和「动作执行能力」拆开。Tool 负责执行动作，Skill 负责约束流程、风格和方法论，二者职责不同。流程如下：
```text
[用户消息进入 Agent]
DefaultAgentFacade.reply(request)
        |
        v
[加载本地 Skill 文档]
workspaceSnapshot.localSkillDocuments()
        |
        v
[解析并选择 Skill]
SkillResolver.resolve(messageBody, localSkillDocuments)
        |
        +---------------------- 显式优先 ----------------------+
        |                                                     |
        | 1) 提取 `$skill-name` / `[$skill-name](...)`        |
        | 2) 按名称归一化匹配 LocalSkillDefinition.name       |
        | 3) 命中 -> ResolvedSkill(..., activation=explicit)  |
        |                                                     |
        +---------------------- 否则自动 ----------------------+
                              |
                              | 1) 遍历每个 Skill 的 keywords
                              | 2) 计算关键词命中分数
                              | 3) 仅“唯一最高分”才命中
                              | 4) 命中 -> ResolvedSkill(..., activation=auto)
                              | 5) 并列最高/0分 -> 不选 Skill
                              v
                    Optional<ResolvedSkill>
        |
        v
[Prompt 组装]
AgentPromptAssembler.appendSelectedSkillSection(...)
(仅 selectedSkill.isPresent() 时注入 Skill 正文)
        |
        v
[Think -> Act -> Observe -> Reply]
模型基于注入后的上下文完成编排与最终回复
        |
        v
[输出结构化信号]
DefaultAgentFacade.buildSignals(selectedSkill)
        |
        +--> 有 Skill: ReplySignal("skill_applied", {skill_name, activation_mode})
        |
        `--> 无 Skill: signals = []
        |
        v
[返回结果]
ReplyEnvelope(body, signals)
```

## Tool / MCP / 安全治理

### Tool System

OpenClaw4J 为本地工具和 MCP 工具提供统一的 Tool System：

- 每个工具都有唯一名称、描述和基于通用 JSON Schema 的输入定义。
- 工具调用结果统一收敛为结构化成功或结构化错误，而不是把原始异常直接抛给渠道层。
- Agent Core 暴露的是统一工具目录，而不是依赖某个特定模型 SDK 的工具定义。
```text
====================== Tool System 主流程（启动期） ======================

[应用启动]
OpenClaw4JApplication
   |
   v
[工具运行时装配]
ToolRuntimeConfiguration
   |
   +--> mcpToolCatalog(...) 初始化 MCP servers，发现 MCP tools
   |
   +--> toolRegistry(...) 合并 Local Tools + MCP Tools
   |       -> LocalToolRegistry
   |
   +--> toolConfirmationService(...) 创建确认流服务
   |
   +--> toolPolicyGuard(...) 创建统一策略层
   |       (参数校验 + 确认态 + 审计)
   |
   `--> toolExecutor(...) 创建 DefaultToolExecutor
            (绑定 ToolRegistry + ToolPolicyGuard)


====================== Tool System 主流程（请求期） ======================

[Agent 决策 tool_call]
DefaultAgentFacade.executeToolStep(...)
   |
   v
[执行入口]
DefaultToolExecutor.execute(ToolCallRequest)
   |
   +--> ToolRegistry.findByName(toolName)
   |       |- 未找到 -> ToolExecutionError(tool_not_found)
   |       `- 找到 -> executeResolvedTool(...)
   |
   v
[策略判定]
ToolPolicyGuard.evaluate(tool, request)
   |
   +--> 参数校验（如 FILESYSTEM_WRITE）
   |       `- 违规 -> DENIED
   |
   +--> 确认策略检查（EXPLICIT）
   |       |- 已确认匹配 -> ALLOWED
   |       `- 未确认 -> 创建 pending confirmation -> CONFIRMATION_REQUIRED
   |
   `--> 低风险/免确认 -> ALLOWED
   |
   +--> 若 DENIED
   |       `- 返回 ToolExecutionError(policy_denied)
   |
   +--> 若 CONFIRMATION_REQUIRED
   |       `- 返回 ToolExecutionError(confirmation_required, confirmationId...)
   |
   `--> 若 ALLOWED
           |
           v
       [真实工具执行]
       tool.execute(request)
           |
           +--> 成功 -> ToolExecutionSuccess
           +--> 参数异常 -> ToolExecutionError(invalid_arguments)
           +--> 工具异常 -> ToolExecutionError(errorCode)
           `--> 未知异常 -> ToolExecutionError(execution_failed)
           |
           v
       [结果审计/确认消费]
       ToolPolicyGuard.recordOutcome(...)
           |- 确认恢复执行场景 -> markConsumed(...)
           `- 普通场景 -> append execution_outcome audit
           |
           v
       [观察回填]
       返回 ToolExecutionResult 给 Agent 编排循环
```
### MCP 集成

当前支持基于 `stdio` 的 MCP server 集成：

- 应用启动时可根据配置初始化多个 MCP server。
- 每个已就绪 server 的工具会被发现并纳入统一工具目录。
- 工具采用稳定内部命名：`mcp.<serverAlias>.<toolName>`。
- MCP 工具与本地工具共享统一的调用、安全和失败降级边界。

```text
====================== MCP 主流程（启动期） ======================

[应用启动]
OpenClaw4JApplication
   |
   v
[创建 MCP Client 工厂]
ToolRuntimeConfiguration.mcpClientFactory(...)
   |
   v
[初始化 MCP 工具目录]
ToolRuntimeConfiguration.mcpToolCatalog(...)
   |
   +--> McpToolCatalog 按配置遍历 servers
   |      (alias + stdio command/args)
   |
   +--> 与每个 server 建立会话
   |      (McpClientSession)
   |
   +--> 拉取 tools/list
   |      -> McpDiscoveredTool
   |
   `--> 生成统一工具名
          mcp.<serverAlias>.<toolName>
   |
   v
[合并注册]
ToolRuntimeConfiguration.toolRegistry(...)
Local Tools + MCP Tools -> LocalToolRegistry
   |
   v
[统一执行器]
ToolRuntimeConfiguration.toolExecutor(...)
DefaultToolExecutor(带 ToolPolicyGuard)


====================== MCP 主流程（请求期） ======================

[模型决策]
DefaultAgentFacade.decideNextAction(...)
   |
   `--> 返回 tool_call: mcp.<alias>.<toolName>
   |
   v
[统一工具执行入口]
DefaultToolExecutor.execute(ToolCallRequest)
   |
   +--> ToolRegistry.findByName(...)
   |      `- 命中 McpBackedTool
   |
   +--> ToolPolicyGuard.evaluate(...)
   |      |- denied -> policy_denied
   |      |- confirmation_required -> 等待显式确认
   |      `- allowed -> 继续
   |
   v
[MCP 调用]
McpBackedTool.execute(...)
   |
   `--> 通过 McpClientSession 调用远端 tool + arguments
   |
   +--> 成功 -> ToolExecutionSuccess(payload)
   `--> 失败 -> ToolExecutionError(errorCode/message)
   |
   v
[结果回填编排]
DefaultAgentFacade.advanceStateAfterObservation(...)
   |
   `--> 继续 Think->Act->Observe 或进入 Final Reply
```

### 安全治理

OpenClaw4J 不把工具安全建立在 prompt 自律上，而是建立在服务端策略层上。

当前安全边界包括：

- 所有工具调用都必须先经过服务端统一策略判定。
- 工具请求会被判定为 `allowed`、`denied` 或 `confirmation_required`。
- 高风险工具请求会持久化待确认状态，只有匹配会话、用户和参数指纹的显式确认才能放行。
- filesystem 写能力会做路径规范化、workspace 根目录约束、敏感路径 denylist 和部分递归/批量语义拦截。
- 工具输出、网页内容、workspace 文件内容和记忆内容都被视为不可信输入，不能绕过服务端安全边界。

这条边界是未来继续扩展更多工具、更多 MCP server 和更复杂编排能力时必须复用的基础。

```text
====================== 安全治理主链路（请求期） ======================

[Agent 产生 tool_call]
DefaultAgentFacade.executeToolStep(...)
   |
   v
[统一执行入口]
DefaultToolExecutor.execute(ToolCallRequest)
   |
   v
[服务端策略判定]
ToolPolicyGuard.evaluate(tool, request)
   |
   +--> 1) 参数级校验（按 ToolSafetyProfile.validatorType）
   |       `- 例如 FILESYSTEM_WRITE 校验路径/敏感目标
   |
   +--> 2) 确认策略检查（ToolConfirmationPolicy）
   |       |- EXPLICIT 且已确认匹配 -> ALLOWED
   |       |- EXPLICIT 且未确认 -> 创建 pending confirmation
   |       |                        -> CONFIRMATION_REQUIRED
   |       `- 非 EXPLICIT -> ALLOWED
   |
   `--> 3) 追加 policy_decision 审计日志
   |
   +----------------------+-------------------------+----------------------+
   |                      |                         |
   | VERDICT=DENIED       | VERDICT=CONFIRMATION   | VERDICT=ALLOWED
   |                      | _REQUIRED              |
   v                      v                         v
返回 ToolExecutionError   返回 ToolExecutionError    进入真实工具执行
(policy_denied)           (confirmation_required,    tool.execute(...)
                          confirmationId...)           |
                                                       +--> success/error 结构化结果
                                                       v
                                            ToolPolicyGuard.recordOutcome(...)
                                              |- 若为确认恢复执行 -> markConsumed
                                              `- 否则写 execution_outcome 审计
                                                       |
                                                       v
                                            返回 ToolExecutionResult 给编排循环

====================== 显式确认恢复执行分支 ======================

[用户下一条消息到达]
DefaultAgentFacade.reply(...)
   |
   v
[解析是否命中待确认请求]
ToolConfirmationService.resolveExplicitConfirmation(...)
   |
   +--> NO_MATCH
   |      `- 回到常规规划路径（继续模型决策）
   |
   +--> REJECTED
   |      `- 生成拒绝观察（不执行工具）-> 最终回复
   |
   `--> CONFIRMED
          `- 恢复原 ToolCallRequest -> executeToolStep(...)
             -> 再次经过 ToolPolicyGuard（应命中已确认）-> 执行工具 -> markConsumed
```
## 可观测设计

1) 设计目标
- 不替代业务日志，而是给「单次运行(run)」提供可串联的结构化时间线。
- 重点支持联调场景：能从 ingress 一路看到 agent/tool/outbound。

2) 核心抽象（生产与消费解耦）
- 业务侧只依赖: `RuntimeObservationPublisher`
  - `createTrace(...)`
  - `emit(...)`
- 输出侧只依赖: `RuntimeObservationSink`
  - `ConsoleRuntimeObservationSink / Noop / Composite`

3) 统一数据模型
- `TraceContext:
  runId, channel, externalConversationId, externalMessageId, internalConversationId, mode`
- `RuntimeObservationEvent:
  timestamp, eventType, phase, level, component, traceContext, payload, verbosePayload`

4) 模式治理（进程级）
- OFF: 不输出
- ERRORS: 只输出 WARN/ERROR（INFO 被过滤）
- TIMELINE: 全时间线，只保留摘要 payload（默认推荐）
- VERBOSE: 在时间线基础上合并「截断后的 verbose 字段」

5) 安全与噪音控制
- verbosePreviewLength 统一截断长字符串，避免把完整上下文直接打出来。
- sink 展示的是「已裁剪后的 payload」，不是原始大对象。

```tex
====================== 可观测主链路（端到端） ======================

[Spring 装配期]
RuntimeObservabilityConfiguration
   |
   +--> runtimeObservationSink(...)
   |      |- console-enabled=true  -> ConsoleRuntimeObservationSink
   |      |- 多 sink               -> CompositeRuntimeObservationSink
   |      `- 无 sink               -> NoopRuntimeObservationSink
   |
   `--> runtimeObservationPublisher(...)
          -> DefaultRuntimeObservationPublisher(mode, previewLength, sink, clock)

[请求运行期]
业务边界（Telegram/DirectMessage/Agent/Tool/Reminder...）
   |
   +--> createTrace(channel, externalConversationId, externalMessageId)
   |      -> 生成 runId，后续事件共享同一个 trace
   |
   `--> emit(trace, eventType, phase, level, component, payload, verbosePayload)
          |
          +--> shouldEmit(mode, level)
          |      |- OFF -> 丢弃
          |      |- ERRORS + INFO -> 丢弃
          |      `- 其余 -> 继续
          |
          +--> mode == VERBOSE ?
          |      |- 是: sanitizeVerbosePayload + truncate
          |      `- 否: 仅摘要 payload
          |
          +--> 组装 RuntimeObservationEvent(timestamp + trace + payload)
          |
          `--> sink.emit(event)
                 |
                 `--> Console sink 单行输出:
                     [run:<runId>][<level>][<phase>][<component>] <eventType + kv...>

```


## 渠道与回复模型

当前系统的消息模型以单聊为核心。

已具备的入口包括：

- 开发用 HTTP 入口：便于本地快速验证统一单聊主链路。
- Telegram 私聊文本 adapter：支持 webhook 鉴权、update 标准化、最终文本回复回传和已知私聊目标的主动消息发送。

统一回复协议为：

- `ReplyEnvelope.body`：用户可见正文。
- `ReplyEnvelope.signals`：结构化系统信号，例如 Skill 应用结果。

当前阶段不支持：

- 流式 token 输出
- 群聊 / 频道 / 线程语义
- 富交互消息块
- 非同步工具协议

## 快速开始

### 环境要求

- Java 25
- Maven Wrapper（仓库已提供 `mvnw` / `mvnw.cmd`）

### 本地启动

在仓库根目录执行：

```bash
./mvnw spring-boot:run
```

Windows PowerShell 可执行：

```powershell
./mvnw.cmd spring-boot:run
```

### 最小配置

项目默认会从根目录 `.env` 加载配置。最基本需要关注的配置包括：

- 模型访问地址与 API Key
- 默认聊天模型
- workspace 根目录
- 服务端口
- 可选的 Telegram 配置
- 可选的 MCP server 配置

一个最小示例：

```env
OPENAI_BASE_URL=http://127.0.0.1:3000
OPENAI_API_KEY=your-api-key
OPENAI_CHAT_MODEL=your-model
SERVER_PORT=8088
OPENCLAW_WORKSPACE_ROOT=./workspace
```

### 开发入口

开发阶段可以直接调用 HTTP 单聊入口验证主链路：

- `POST /api/direct-messages`

也保留了一个简单调试入口：

- `GET /test/simple/chat`

其中前者更接近统一 Agent 主链路，后者更适合做底层模型连通性验证。

## 核心配置

以下配置类别最值得优先理解：

- 模型配置：`spring.ai.openai.*`
- workspace 配置：`openclaw.workspace-root`
- 最近上下文轮次：`openclaw.recent-turn-limit`
- 编排预算：`openclaw.orchestration.max-steps`
- 可观测性：`openclaw.observability.*`
- Telegram：`openclaw.telegram.*`
- MCP：`openclaw.mcp.*`
- reminder：`openclaw.reminder.*`
- scheduler：`openclaw.scheduler.*`
- memory：`openclaw.memory.*`

建议把 `application.yaml` 视为默认行为说明，把 `.env` 视为本地部署入口。

## Telegram 接入

OpenClaw4J 当前已经具备 Telegram 私聊文本 adapter，支持从 webhook 入站到最终文本回复回传的最小真实链路。

启用 Telegram 之前，至少需要准备：

- `OPENCLAW_TELEGRAM_ENABLED=true`
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_WEBHOOK_SECRET`
- `TELEGRAM_WEBHOOK_URL`

当前 Telegram 接入边界是：

- 只支持私聊文本消息。
- 只发送最终一次性纯文本回复。
- 不自动注册 webhook，需要开发者或运维手动调用 Telegram Bot API。
- 本地联调需要公网可访问的 HTTPS 地址，不能直接使用 `localhost`。

更完整说明见 `docs/telegram-dm-adapter.md`。

## 开发与测试

项目当前以分层模块组织：

- `channel`：渠道入口与 adapter
- `agent`：统一 Agent Core
- `config`：集中配置绑定
- `conversation`：内部会话与身份映射
- `memory`：记忆文件与索引
- `observability`：运行期可观测性
- `reminder`：提醒与调度
- `skill`：Skill 解析与选择
- `tool`：本地工具、MCP 工具与安全治理
- `workspace`：workspace 加载

运行测试可执行：

```bash
./mvnw test
```

如果你想理解当前系统边界，除了阅读源码，建议优先查看：

- `openspec/specs/**`
- `docs/runtime-observability.md`
- `docs/telegram-dm-adapter.md`

## Roadmap

当前版本已经覆盖单聊主链路、基础 Agent Core、Tool System、MCP 接入、Memory V1、Reminder V1 和 Telegram adapter 的最小闭环。

后续演进方向包括：

- 第二个真实渠道 adapter
- 更强的智能体编排能力与受控委派
- 更完整的记忆治理能力
- 更细粒度的工具安全策略
- 更成熟的运维与可观测性能力
- 更完整的 workspace 文件体系与 Skill 基础设施

Roadmap 表示方向，不等同于当前稳定契约。

## 非目标

当前阶段明确不承诺以下内容：

- 群聊、频道、线程楼层等复杂消息语义
- 开发型工具能力，例如 shell、git、patch 等
- 异步工具协议
- 跨渠道同一用户自动合并
- Skill 市场、远程安装与 Skill 自我改写
- 富交互 UI 或流式回复体验

这些边界是有意为之，目的是先把单聊 Agent 内核、workspace 模型和安全执行链路打稳。

## 相关文档

- `docs/telegram-dm-adapter.md`
- `docs/runtime-observability.md`
- `docs/checklist.md`
- `openspec/specs/**`

## License

本项目采用 `MIT` 许可证发布，详见仓库根目录 [LICENSE](LICENSE) 文件。

## Acknowledgements

OpenClaw4J 构建于 Spring Boot、Spring AI 和本地文件驱动 Agent 设计理念之上。

项目的重点不在于包装一次模型调用，而在于探索一个可本地积累、可解释、可治理、可扩展的 Java Agent 内核。
