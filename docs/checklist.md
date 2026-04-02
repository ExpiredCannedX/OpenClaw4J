# OpenClaw4j 功能清单

## Summary
- OpenClaw4j 不只是一个聊天接口，而是一个能接收消息、理解任务、调用工具、保存记忆、执行定时任务并利用历史知识回答问题的 Agent 系统。
- 一个完整的 OpenClaw4j，至少要具备 7 类核心能力：Channel、Agent Core、Tool System、Memory、Scheduler、RAG、Ops / Safety。
- 这份文档只作为功能清单，用于后续继续展开成 PRD、roadmap 或任务拆解。

## 必须实现的功能

### 1. Channel
- 统一的输入输出消息模型。
- 统一的渠道抽象层。
- 至少一个可运行渠道。
- 每个渠道都要支持：
- 接收消息。
- 发送回复。
- 识别用户、会话、线程。
- 去重和幂等处理。
- 长耗时请求的基础反馈。

### 2. Agent Core
- 一个统一的 Agent 入口。
- 上下文组装能力。
- 模型调用和决策能力。
- 最终回复生成能力。
- 基础能力至少包括：
- 单轮问答。
- 多轮会话。
- 系统提示词。
- 错误兜底回复。
- 超时和重试。

### 3. Tool System
- 本地工具定义机制。
- 工具注册中心。
- 模型可见的工具描述。
- 工具执行结果回填给模型。
- 第一批工具建议只做：
- 时间和日期。
- 记忆读写。
- 提醒创建。
- 可选搜索。

### 4. Memory
- 短期记忆：
- 当前会话历史。
- 上下文裁剪。
- 长期记忆：
- `MEMORY.md`
- `USER.md`
- `SOUL.md`
- `TOOLS.md`
- `daily/*.md`
- 记忆操作能力：
- remember
- search
- update
- forget
- event log

### 5. Scheduler
- 一次性提醒。
- cron 提醒。
- 后台 heartbeat。
- 定时任务状态持久化。
- 提醒触发后回到正确渠道发送消息。

### 6. RAG
- 历史消息索引。
- embedding 生成。
- 向量检索。
- `RAGTool` 或自动注入检索结果。
- 至少支持：
- “上周讨论过什么”。
- “这个问题之前怎么定的”。

### 7. Ops / Safety
- 配置中心化。
- API Key / Token 环境变量化。
- 日志和 tracing。
- 健康检查。
- 工具失败恢复。
- 模型空响应恢复。
- 对高风险外部操作做确认。

## 推荐分阶段实现

### P0：最小可用版
- 一个 Channel。
- 单轮聊天。
- 多轮上下文。
- 基础系统提示词。
- 基础错误兜底。

### P1：像 Agent 的版本
- 本地工具系统。
- 短期记忆。
- 长期记忆。
- Reminder / Scheduler。
- 简单 ReAct tool calling。

### P2：像 OpenClaw 的版本
- 第二个 Channel。
- 外部工具或 MCP。
- RAG。
- 可观测性。
- 更完整的安全机制。

### P3：增强版
- 多渠道统一行为。
- 更强的工具编排。
- 进度消息 / 消息编辑。
- 更细的 profile / persona。
- 更成熟的部署和运维能力。

## 实现顺序建议
1. Channel 基础。
2. Agent Core。
3. 多轮记忆。
4. Tool System。
5. 长期记忆。
6. Scheduler。
7. 第二个 Channel。
8. RAG。
9. Ops / Safety。

## Assumptions
- 这里说的是“像 OpenClaw 的 Java 版本”，不是单纯一个聊天接口。
- 所以“能对话”只是起点，不算完整 OpenClaw4j。
- 一个完整的 OpenClaw4j，最少要同时具备：Channel、Agent Core、Tool System、Memory、Scheduler。
