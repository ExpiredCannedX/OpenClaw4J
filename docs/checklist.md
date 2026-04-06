# OpenClaw4J 需求清单

## 产品定位
- OpenClaw4J 是一个本地优先、文件驱动、可持续积累的通用消息 Agent 系统。
- 模型不是记忆本体，Workspace 才是 Agent 的长期资产；模型负责执行，Workspace 负责沉淀人格、规则、技能、经验与记忆。
- V1 聚焦 IM/Bot 单聊场景，先做通用消息 Agent 内核，再适配飞书、QQ、微信生态等渠道。
- OpenClaw4J 的目标不是单纯“能聊天”，而是能在本地持续学习、检索历史、调用工具、执行提醒并稳定复用 Skill。

## 核心设计原则
- 本地优先：人格、规则、技能、经验、长期记忆默认保存在本地文件与本地 SQLite 中。
- 文件驱动：核心知识以 Markdown 为主，SQLite 负责检索、索引与结构化映射。
- 单聊优先：V1 只做单聊通用 Agent，不提前设计群聊、频道、线程楼层等复杂语义。
- 显式优先：用户级长期记忆采用显式触发优先，自动写入仅限白名单低风险字段。
- 渠道解耦：Agent Core 不直接依赖飞书、QQ、微信等平台 payload，统一通过内部消息模型工作。
- Skill 与 Tool 分离：Tool 提供动作能力，Skill 提供流程、规则、SOP 与方法论。
- 可解释与可追踪：Skill 自动激活、记忆写入、工具失败都必须可追踪、可恢复、可审计。
- 注释优先：编码时必须编写清晰注释，类注释说明职责、使用场景与边界；方法注释说明为什么这样做、如何做、关键约束与必要的实现意图。

## 状态约定
- 当前已归档能力：已经沉淀到 `openspec/specs/**` 主 spec，可视为当前系统契约或已确认的能力边界。
- Roadmap 目标：方向性目标、后续阶段计划或待实现能力，不等同于当前实现，也不等同于当前稳定契约。
- 非目标 / 延后项：当前阶段明确不承诺的内容，只有在后续 change 明确提出后才进入当前契约。

## 当前已归档能力总览
- `single-dm-channel`：开发用单聊入口、真实渠道入口抽象、内部消息标准化、内外部身份映射、活跃会话复用、内部会话到渠道回发目标绑定与幂等去重。
- `single-dm-agent-core`：统一 Agent 入口、`ReplyEnvelope(body + signals)`、最近会话与 workspace 上下文组装、至多一次同步工具闭环、最终一次性回复与失败兜底。
- `workspace-bootstrap`：`SOUL.md`、`SKILLS.md`、`USER.md`、`MEMORY.md` 的基础加载，静态规则 / 动态记忆 / 本地 Skill 文档分层，以及 `skills/**/SKILL.md` 的稳定发现。
- `skill-resolution`：`SKILL.md` front matter 解析、显式 Skill 优先、保守自动匹配、单次请求最多选择一个 Skill。
- `tool-system`：统一工具定义、唯一命名注册中心、同步执行器、结构化成功/失败结果、内置 `time` 工具。
- `mcp-tool-integration`：`stdio` MCP server 配置、`required/optional` 启动策略、启动期 tool discovery、`mcp.<serverAlias>.<toolName>` 命名和统一同步调用/降级边界。
- `runtime-observability`：运行期观测模式、run 级 trace identity、稳定生命周期事件、console sink、按模式裁剪负载。
- `telegram-dm-adapter`：Telegram webhook 鉴权、私聊文本 update 翻译、最终一次性文本回复回传，以及基于已知私聊目标的主动文本发送。
- `agent-memory`：单用户 workspace 记忆体系、`USER.md` 白名单写入、本地 SQLite 索引、`memory.search`、`memory.remember`，以及显式 FTS5 tokenizer 策略。
- `scheduler-reminders`：`reminder.create`、一次性提醒持久化、固定 heartbeat 调度、失败重试，以及按内部会话回到原渠道发送提醒。

## 核心能力分层

### 1. Gateway / Channel
**当前已归档能力**
- 系统已经具备开发用单聊入口和至少一个真实渠道入口的统一接入边界。
- 外部消息会被标准化为内部消息模型，再进入统一 Agent 入口。
- 渠道原生 `user_id`、`conversation_id`、`message_id` 已通过外部映射绑定到平台无关的内部标准 ID。
- 单聊场景下默认复用“同一渠道同一用户”的活跃会话。
- 系统已经为活跃内部会话维护渠道回发目标绑定，供提醒等异步能力复用。
- 渠道层已支持幂等去重，避免同一外部消息重复触发 Agent。
- Telegram 私聊文本消息已完成 webhook-first 接入、鉴权、最终一次性文本回复回传和主动文本回发。

**Roadmap 目标**
- 接入更多真实渠道 adapter，例如飞书、QQ、微信等。
- 在不破坏统一内部模型的前提下，逐步支持更多消息类型和更丰富的回复语义。
- 继续保持单聊优先，不提前把群聊、频道、线程楼层等复杂语义塞进 V1 主链路。

### 2. Agent Core
**当前已归档能力**
- 系统已经具备统一的 Agent 入口，输入标准化单聊请求，输出结构化 `ReplyEnvelope`。
- 当前上下文组装边界已经明确，包括 workspace 内容、选中的 Skill、最近会话、本地工具与已就绪 MCP 工具目录，以及工具执行后的结构化观察结果。
- 当前请求内最多允许一次同步工具调用，再收敛为一条最终一次性回复。
- 模型失败、工具失败或工具不可用时，系统会转换为结构化观察或安全兜底回复，而不是把原始异常抛给渠道层。
- 当前 Reply 模型锁定为 one-shot final reply，不支持流式 token、消息编辑或中途进度推送。

**Roadmap 目标**
- 优先把执行循环扩展到更完整的 `Load -> Think -> Act -> Observe -> Reply`，并在已完成的 MCP Tool 接入之上补齐多步工具编排与智能体编排基础。
- 在保持 one-shot reply 契约的前提下，逐步支持更复杂的工具编排、多步决策、受控委派与后续上下文压缩能力。
- Learn 阶段写回继续保留为远期方向，但当前不作为主线优先事项。

### 3. Workspace
**当前已归档能力**
- Workspace 已被明确为 Agent 的本地长期资产，并且当前 bootstrap 只保证最小上下文装配所需文件。
- 当前主 spec 认定的最小核心文件为 `SOUL.md`、`SKILLS.md`、`USER.md`、`MEMORY.md`。
- 当前本地 Skill 发现路径为 `skills/**/SKILL.md`，并且会和静态规则、动态记忆分开管理。
- `memory/*.md` 已经进入 memory 事实源和索引范围，但不是 bootstrap 阶段自动注入的核心上下文文件。
- 缺失的可选文件或目录不会导致请求失败，只会被视为空上下文。
- 当前阶段默认不允许模型自动改写高风险静态规则文件，如 `SOUL.md`、`SKILLS.md`、`AGENTS.md`。

**Roadmap 目标**
- 将 `AGENTS.md`、`LEARNINGS.md`、`ERRORS.md` 等文件纳入更完整的 workspace 文件体系。
- 继续细化“静态规则 / 动态记忆 / 经验沉淀 / 委派策略”的文件职责边界。
- 在不破坏文件驱动哲学的前提下，补齐更多受控写回与治理规则。

### 4. Skill System
**当前已归档能力**
- 系统已支持本地 `SKILL.md` front matter 解析，识别 `name`、`description`、`keywords` 等规范字段，并忽略未知字段。
- 当前显式 Skill 选择优先于自动匹配。
- 当前自动匹配仍是保守策略，只基于关键词规则，不做语义召回。
- 当前一次请求内最多自动匹配一个 Skill。
- 当前只要成功选中 Skill，就会产生结构化 `skill_applied` signal。

**Roadmap 目标**
- 明确完整的 Skill 优先级链，例如“用户显式指定 > workspace 自定义 > 内置 Skill > 无 Skill”。
- 增加同一会话连续命中时的 signal 去重策略。
- 建立渐进式 Skill 加载机制，区分 Discovery、Activation、Execution 三层。
- 后续如需要，再评估更复杂的匹配、裁剪和冲突消解机制。

### 5. Tool System
**当前已归档能力**
- 当前 Tool System 已具备统一 Tool API，包括工具名、描述、基于通用 JSON Schema 的输入 schema、成功结果和错误结果。
- 当前已具备唯一命名的工具注册中心和同步执行器。
- 当前已具备基于 `stdio` 的 MCP server 配置、启动期 tool discovery、`required/optional` 启动策略，以及 `mcp.<serverAlias>.<toolName>` 内部唯一命名规则。
- 当前 Agent Core 已能把本地工具与已发现 MCP 工具的统一目录暴露给模型，并在一次请求内完成“决策 -> 执行一次工具 -> 回填观察 -> 最终回复”的最小闭环。
- 当前已归档的本地工具包括 `time`、`memory.search`、`memory.remember`、`reminder.create`。
- 当前阶段仍明确限制为同步请求-响应型工具，不支持异步工具、后台句柄或进度流。

**Roadmap 目标**
- 补齐更多业务工具，例如 `reminder.list`、`reminder.update`、`reminder.cancel` 等提醒治理能力。
- 围绕已接入的 MCP Tool foundation 继续补齐工具编排、超时/失败治理与可观测性边界，为后续智能体编排提供稳定执行基座。
- 逐步扩展更多业务工具，并在需要时再评估异步工具协议。

### 6. Memory / Retrieval
**当前已归档能力**
- 当前 workspace 明确锁定为单用户，根级 `USER.md` 可以承载该唯一用户的稳定画像。
- 当前记忆体系已分层为 `USER.md`、`MEMORY.md` 和 `memory/YYYY-MM-DD.md`。
- 当前 `USER.md` 写入只允许白名单稳定画像类别，不允许把短期计划或未确认推断写进去。
- 当前记忆 provenance 来自运行时上下文，而不是模型传参。
- 当前已具备本地 SQLite 单文件索引，事实源仍是 Markdown 文件。
- 当前 `memory.search` 已升级为“可匹配长词走 FTS5 `MATCH` 主路径，过短查询项走确定性 substring fallback”的检索模型。
- 当前 memory 索引具备显式 FTS tokenizer 策略、schema version 判定和旧索引整体重建能力。
- 当前 `memory.search` 会保留 scope 过滤，并返回带正向 score 的结构化结果。
- 当前 `memory.remember` 写入成功后会同步刷新受影响文件的索引。
- 当前检索结果只通过显式工具调用回填，不会自动注入 bootstrap 或 planning prompt。

**Roadmap 目标**
- 增加 `memory.update`、`memory.forget`、`memory.get`、`event log` 等更完整的记忆操作能力。
- 引入向量检索、混合检索、分数归一化、时间衰减与更细粒度的增量索引。
- 评估是否需要 watcher、上下文自动注入、摘要压缩和更复杂的 profile merge。
- `LEARNINGS.md`、`ERRORS.md` 的经验性写回保持最低优先级，避免干扰当前 memory V1 foundation、MCP Tool foundation 演进与智能体编排主线。

### 7. Scheduler
**当前已归档能力**
- 当前已经具备一次性提醒能力，包括同步 `reminder.create`、本地持久化、固定 heartbeat 扫描、失败重试和终态失败处理。
- 当前提醒触发后已经能够按内部 `ConversationId` 解析回正确渠道目标并回发纯文本提醒。
- 当前 Scheduler 仍建立在单进程本地部署前提上，不承诺跨崩溃窗口的精确一次投递。

**Roadmap 目标**
- 扩展 cron / 重复提醒、更完整的提醒治理能力和更细粒度的任务状态管理。
- 继续提升提醒触发后的恢复能力、投递幂等性和多渠道主动回发支撑。
- 保持 Scheduler 与 Channel 之间通过内部会话映射解耦。

### 8. Reply / Signal Model
**当前已归档能力**
- 当前回复协议已经锁定为统一结构化 `ReplyEnvelope`。
- 当前 `ReplyEnvelope` 采用 `body + signals` 极简模型。
- 当前系统已明确把 runtime observation 与用户可见 reply payload 分离。
- 当前 `skill_applied` 是已经存在的结构化 signal。
- 当前 reply 仍然只支持最终一次性回复。

**Roadmap 目标**
- 继续扩展更多结果级 signal，而不是把系统语义混入正文。
- 在各渠道 adapter 中逐步补齐 signal 的平台化渲染与降级策略。
- 如后续确有必要，再评估 richer reply blocks 或更复杂的用户可见结构。

### 9. Ops / Safety
**当前已归档能力**
- 当前运行期可观测性已具备 `OFF / ERRORS / TIMELINE / VERBOSE` 模式，并默认使用摘要 timeline 模式。
- 当前每次消息处理都具备 run-scoped trace identity。
- 当前系统已在 ingress、agent、MCP server 初始化、tool discovery、tool、reply dispatch 和 failure 边界上发出结构化生命周期事件。
- 当前已具备 pluggable sink 抽象与 console sink。
- 当前详细负载暴露会按观测模式裁剪，不会默认泄漏完整 prompt、workspace、MCP 命令环境或原始模型输出。
- 当前 memory 写入、memory 检索、索引刷新、提醒创建、提醒调度与工具失败已经具备明确的失败边界和观测留痕。

**Roadmap 目标**
- 继续补齐健康检查、更多 sink、事件持久化与更系统化的运维面。
- 把高风险外部操作确认、审计策略和更完整的安全治理做成稳定能力。
- 在不破坏本地优先原则的前提下，逐步扩展更成熟的恢复与治理机制。

## 工具调用安全治理优先级
**当前已知边界**
- 当前一次请求内最多允许一次同步工具调用，这会限制多步工具链的破坏半径，但不构成真正的安全边界。
- 当前 workspace bootstrap 只会把 `SOUL.md`、`SKILLS.md`、`USER.md`、`MEMORY.md` 和本地 Skill 文档注入上下文，不会自动把整个目录树直接暴露给模型。
- 当前 `filesystem` MCP server 的可访问范围已经被限制在 workspace 根目录内，但这仍然只是能力边界，不等同于显式安全策略。
- 当前系统尚未形成独立的服务端工具安全策略层；高风险工具确认、风险分级、敏感路径治理、拒绝策略和审计策略都还不是当前稳定契约。

**相对当前阶段的优先级结论**
- 应先补最小安全边界，再继续扩展更复杂的 Agent 编排能力。
- 真正可信的安全拦截点必须放在“模型完成编排之后、真实工具执行之前”，不能只依赖 prompt 提示或模型自觉。
- 编排前的提示约束只负责降低误触发概率；编排后的服务端策略层才是最终生效的硬边界。

### 安全 P0
- 在工具执行前增加统一的服务端策略层，对工具进行 `read_only / state_changing / destructive / external_network` 风险分级。
- 对高风险工具默认拒绝，只有在命中显式确认态时才允许执行；确认状态必须由服务端持久化和校验，不能只依赖模型回复“请确认”。
- 对 `filesystem` 等可写工具增加参数级安全校验，包括路径规范化、相对路径解析、符号链接检查、workspace 白名单、敏感文件 denylist，以及批量/递归修改限制。
- 把工具输出、网页内容、workspace 文件内容和记忆内容都视为不可信输入，建立 prompt injection 的最小防护边界。
- 为高风险工具调用建立结构化审计日志，明确记录“模型请求了什么”“系统最终放行了什么”“是否经过确认”“最终执行结果如何”。
- 为恶意提示词、路径逃逸、prompt injection、确认流和拒绝流建立回归测试，确保安全边界不会在后续编排演进中退化。

### 安全 P1
- 在规划 prompt 中补充高风险工具使用约束、确认引导和更友好的拒绝文案，但明确其定位只是辅助约束而不是最终安全边界。
- 将 `filesystem` 的读能力和写能力做进一步拆分，优先向模型暴露只读能力，写能力按场景和策略显式开启。
- 为高风险文件修改提供 dry-run / preview 能力，让系统可以先生成“将修改哪些文件、如何修改”的预览，再进入确认流。
- 为不同工具、不同路径和不同会话类型补充更细粒度的 allowlist / denylist 策略。

### 安全 P2
- 在多步工具编排、受控委派、自动化任务和后续智能体编排链路中复用统一策略层，不允许新的执行链路绕过既有安全检查。
- 扩展更细粒度的安全域和策略模型，例如按工具、按路径、按会话信任级别和按渠道类型施加不同执行边界。
- 增加更完整的异常检测、风险告警和人工审计能力，让系统具备持续治理而不是一次性拦截。

## 核心数据与身份策略
**当前已归档能力**
- `ConversationId` 已明确使用平台无关的内部标准 ID。
- 渠道原生 `user_id`、`conversation_id`、`message_id` 已通过外部映射绑定到内部实体。
- 当前单聊默认同一渠道同一用户只保留一个活跃会话。
- 历史会话应通过归档或切换处理，而不是覆盖旧会话。

**Roadmap 目标**
- 在不破坏现有映射模型的前提下，扩展更完整的历史会话管理与恢复能力。
- 继续保持 V1 不做人类意义上的跨渠道同一用户自动合并。

## 上下文组装顺序
**当前已归档能力**
- 当前上下文组装以 `SOUL.md`、`SKILLS.md`、选中的 Skill、动态记忆、最近会话和工具目录为核心。
- 当前工具执行后会把结构化工具观察结果回填到最终回复阶段。
- 当前显式 `memory.search` 的结果属于工具观察，而不是自动检索召回注入。

**Roadmap 目标**
- 在后续 change 中继续讨论“当前会话摘要”“长期记忆召回”“自动注入策略”和“上下文压缩”的更完整排序。
- 只有当相关能力被新的主 spec 明确后，才把自动检索召回写成当前契约。

## Roadmap 分阶段目标

### 已归档覆盖范围
- 当前已基本覆盖 P0 的单聊主链路、统一 Agent 入口、基础 workspace 加载、最终一次性回复和基础错误兜底。
- 当前已覆盖 P1 的一部分，包括 Skill resolution、同步 Tool System foundation、运行期可观测性、本地 memory foundation、`reminder.create` 与 Scheduler V1，以及 `memory.search` 的 FTS 升级。

### P1 剩余目标
- 智能体编排基础，包括多步工具编排、受控委派与失败恢复边界。
- 更完整的 Tool / Agent 执行闭环与上下文压缩。

### P2 目标
- 第二个渠道 adapter。
- 更完整的 workspace 文件体系。
- Skill Catalog 与渐进式加载基础设施。
- 混合检索，包括 FTS5、sqlite-vec、去重、分数归一化和时间衰减。
- 更完整的可观测性与安全机制。
- 把 `AGENTS.md` 驱动的委派策略收敛为稳定的智能体编排契约。

### P3 目标
- 多渠道统一行为。
- 更强的跨渠道工具编排与多 Agent 协作增强。
- Skill 执行支撑层增强。
- 更成熟的 profile / persona 演进。
- `LEARNINGS.md`、`ERRORS.md` 写回与更完整的 Learn 阶段闭环。
- 更完整的部署和运维能力。

## 建议实现顺序（Roadmap）
1. 先补齐工具调用安全 P0，包括服务端策略层、风险分级、确认流、路径白名单与敏感文件 denylist、审计日志，以及恶意提示词 / 路径逃逸 / prompt injection 回归测试。
2. 在最小安全边界稳定后，再补齐智能体编排基础，包括多步工具编排、受控委派、超时/失败治理与观测边界。
3. 接入第二个真实渠道 adapter。
4. 推进混合检索和更完整的记忆演进能力。
5. 最后再收敛 `LEARNINGS.md` / `ERRORS.md` 写回与更完整的 Learn 阶段闭环。

## 非目标
- V1 不做 ClaudeCode 风格的 workspace runtime。
- V1 不做 `shell`、文件补丁、`git` 等开发型工具能力。
- V1 不做异步工具协议。
- V1 不做群聊、频道、线程楼层等复杂消息语义。
- V1 不做跨渠道同一用户自动合并。
- V1 不做 Skill 市场、远程安装、Skill 自动改写自身。

## Assumptions
- 这里说的是“像 OpenClaw 的 Java 版本”，但当前阶段聚焦消息驱动的 Agent 内核。
- 一个完整的 OpenClaw4J，价值来自 workspace 的持续积累，而不是模型本身。
- 复制 workspace 应能够迁移 Agent 的人格、规则、技能和长期记忆。
