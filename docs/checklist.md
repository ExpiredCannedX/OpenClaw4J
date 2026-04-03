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

## 核心能力

### 1. Gateway / Channel
- 统一的渠道入口与消息路由。
- 统一的输入输出消息模型。
- 渠道 adapter 负责签名校验、事件接收、消息发送、重试与错误转换。
- 至少一个可运行 IM/Bot 渠道。
- V1 只支持单聊语义。
- 每个渠道都要支持：
- 接收消息。
- 发送最终一次性回复。
- 识别用户、会话、消息。
- 去重和幂等处理。
- 渠道原生 ID 映射到平台无关的内部标准 ID。
- 平台无关的内部标准 ID + 外部映射表。
- 单聊默认“同一渠道同一用户复用一个活跃会话，记忆连续”。

### 2. Agent Core
- 一个统一的 Agent 入口。
- 采用 `Load -> Think -> Act -> Observe -> Learn -> Reply` 执行循环。
- 上下文组装能力。
- 模型调用和决策能力。
- Skill 解析与选择能力。
- Tool 调用编排能力。
- Learn 阶段的记忆与经验写回能力。
- 最终回复生成能力。
- 基础能力至少包括：
- 单轮问答。
- 多轮会话。
- 系统提示词。
- 错误兜底回复。
- 超时和重试。
- 最终一次性回复。

### 3. Workspace
- 每个 Agent 拥有独立本地 workspace。
- Workspace 是 Agent 的灵魂和长期资产。
- Workspace 以 Markdown 文件为主，以 SQLite 为索引与检索层。
- V1 必须支持的核心文件：
- `SOUL.md`
- `USER.md`
- `SKILLS.md`
- `LEARNINGS.md`
- `ERRORS.md`
- `MEMORY.md`
- `memory/*.md`
- 可预留但 V1 不强依赖：
- `AGENTS.md`
- 静态规则文件与动态记忆文件要区分管理。
- V1 默认不允许模型自动改写高风险静态规则文件：
- `SOUL.md`
- `AGENTS.md`
- `SKILLS.md`

### 4. Skill System
- Skill 是方法论、规则、SOP、模板与最佳实践，不直接替代 Tool。
- 支持本地 Skill 发现与加载。
- 支持 `SKILL.md` 元数据解析。
- 支持用户显式指定 Skill。
- 支持基于关键词/规则的自动匹配推荐。
- Skill 优先级链：
- 用户显式指定 Skill
- workspace 自定义 Skill
- 内置 Skill
- 无 Skill
- 自动匹配结果必须经过 resolver 裁剪，避免 prompt 膨胀和 Skill 冲突。
- V1 自动匹配基于关键词/规则，不做语义召回。
- 显式指定 Skill 直接激活。
- 自动匹配 Skill 默认允许自动激活，但要保守控制数量与冲突。
- V1 默认最多自动激活 1 个 Skill。
- Skill 自动激活后必须产生结构化 `skill_applied` signal。
- 同一 Skill 在同一会话中连续命中默认只提示一次。
- Skill 变化时必须重新提示。

### 5. Tool System
- 统一 Tool API：
- 工具名称
- 参数模式
- 返回结构
- 错误结构
- 本地工具定义机制。
- 工具注册中心。
- 工具执行器。
- 模型可见的工具描述与 schema。
- 工具执行结果结构化回填给模型。
- 支持内置工具与基于 MCP 的外部工具接入。
- Agent Core 不直接依赖 MCP 协议，而统一通过 Tool System 调用工具。
- V1 仅支持同步请求-响应型工具。
- V1 不支持异步工具、后台任务句柄、进度流或回调通知。
- V1 的 MCP 集成范围仅限 Tool Discovery 与 Tool Invocation，不要求支持 Resource、Prompt 等非工具能力。
- V1 内置工具最小集合：
- `time`
- `memory.search`
- `memory.remember`
- `reminder.create`

### 6. Memory / Retrieval
- 采用双层记忆：
- `User Profile Memory`
- `Conversation History Memory`
- 用户与会话关系允许一对多。
- V1 单聊策略为“同一渠道同一用户唯一活跃会话”，但模型层必须允许历史会话归档与切换。
- 用户级记忆存放跨会话稳定信息：
- 称呼
- 偏好
- 习惯
- 禁忌
- 稳定约束
- 会话级记忆存放当前对话历史、摘要与最近意图。
- 长期记忆默认保守，显式触发优先。
- 自动写入仅允许白名单低风险字段。
- 所有长期记忆写入都必须记录来源、时间、渠道、触发原因与可选置信度。
- 必须支持：
- `remember`
- `search`
- `update`
- `forget`
- `event log`
- 长期记忆文件至少包括：
- `USER.md`
- `LEARNINGS.md`
- `ERRORS.md`
- `MEMORY.md`
- `memory/*.md`
- 检索层采用 SQLite，本地单文件部署。
- V1 至少支持 FTS 全文检索。
- 后续支持混合检索：
- FTS5 关键词检索
- sqlite-vec 向量检索
- 向量与关键词结果的去重与分数归一化
- 时间衰减规则
- 增量索引
- 检索结果用于辅助上下文组装，不等于直接写回长期记忆。

### 7. Scheduler
- 一次性提醒。
- cron 提醒。
- 后台 heartbeat。
- 定时任务状态持久化。
- 提醒触发后按内部 `ConversationId` 回到正确渠道发送消息。
- Scheduler 与 Channel 之间通过内部会话映射解耦。

### 8. Reply / Signal Model
- Agent 回复必须采用统一结构化 `ReplyEnvelope`。
- V1 的 `ReplyEnvelope` 采用 `body + signals` 极简模型。
- `body` 只承载用户可读正文。
- `signals` 承载结构化系统语义。
- Skill 自动激活时必须生成 `skill_applied` signal。
- 渠道层负责将 signal 渲染为平台能力允许的结构化提示，不能渲染时降级为标准文本。
- 同一 Skill 在同一会话中连续命中默认只提示一次。
- 结构化 signal 不应污染正文、摘要和长期检索语料。
- V1 先只支持最终一次性回复。
- V1 不支持流式 token、进度事件、消息编辑或中途状态推送。

### 9. Ops / Safety
- 配置中心化。
- API Key / Token 环境变量化。
- 日志和 tracing。
- 健康检查。
- 工具失败恢复。
- 模型空响应恢复。
- 记忆写回失败恢复。
- 索引失败恢复。
- Skill 选择与记忆写入的 provenance 留痕。
- 对高风险外部操作做确认。
- Bootstrap 加载时限制 Markdown 体积，避免上下文膨胀。
- 支持文件变更后的增量索引。

## 核心数据与身份策略
- `ConversationId` 必须使用平台无关的内部标准 ID。
- 渠道原生 `user_id`、`conversation_id`、`message_id` 通过外部映射表绑定到内部实体。
- `User -> Conversation` 允许一对多。
- V1 单聊默认同一渠道同一用户只保留一个活跃会话。
- 历史会话应可归档，不通过覆盖旧会话实现“重新开始”。
- V1 不做人类意义上的跨渠道同一用户自动合并。

## 上下文组装顺序
- `SOUL.md` 与静态规则。
- 选中的 Skill。
- 用户级记忆。
- 当前会话摘要。
- 最近若干轮对话。
- 检索召回的长期记忆。
- 工具上下文。

## 推荐分阶段实现

### P0：最小可用单聊 Agent
- 一个 IM/Bot 渠道 adapter。
- 统一消息模型与内部 ID 映射。
- Agent Core 基础执行循环。
- `SOUL.md`、`USER.md`、`MEMORY.md` 的基础加载。
- 多轮上下文。
- 最终一次性回复。
- 基础错误兜底。

### P1：像 Agent 的版本
- Skill System（显式指定 + 关键词/规则自动匹配）。
- 同步 Tool System（内置工具 + MCP Tool 接入）。
- 双层记忆。
- `LEARNINGS.md`、`ERRORS.md` 写回。
- Reminder / Scheduler。
- FTS 全文检索。
- 简单 ReAct tool calling。

### P2：像 OpenClaw 的版本
- 第二个渠道 adapter。
- 完整 workspace 文件体系。
- 混合检索：
- FTS5
- sqlite-vec
- 去重与分数归一化
- 时间衰减与增量索引。
- 更完整的可观测性与安全机制。

### P3：增强版
- 多渠道统一行为。
- 更强的工具编排。
- 多 Agent 协作。
- `AGENTS.md` 驱动的委派策略。
- 更成熟的 profile / persona 演进。
- 更完整的部署和运维能力。

## 实现顺序建议
1. Gateway / Channel 基础。
2. 统一消息模型与内部 ID 映射。
3. Agent Core。
4. Workspace 基础加载。
5. ReplyEnvelope 与 signal 渲染。
6. Skill System。
7. 双层记忆。
8. Tool System。
9. Scheduler。
10. 第二个渠道 adapter。
11. 混合检索。
12. 更完整的 Ops / Safety。

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
