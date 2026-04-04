# 运行期可观测性

## 目标

当前仓库已经为统一单聊主链路补上了结构化运行事件。它的目标不是替代业务日志，而是让开发者在联调 Telegram 或 direct-message 入口时，能直接看到一次消息处理从 ingress 到最终回复发送的时间线。

## 如何开启

默认配置已经在 [`application.yaml`](/D:/QuashyFlies/Project/OpenClaw4J/src/main/resources/application.yaml) 中声明为 `TIMELINE` 模式，并启用 console sink：

```yaml
openclaw:
  observability:
    mode: TIMELINE
    console-enabled: true
    verbose-preview-length: 160
```

也可以通过环境变量覆盖：

```powershell
$env:OPENCLAW_OBSERVABILITY_MODE="VERBOSE"
$env:OPENCLAW_OBSERVABILITY_CONSOLE_ENABLED="true"
$env:OPENCLAW_OBSERVABILITY_VERBOSE_PREVIEW_LENGTH="240"
```

可用模式如下：

- `OFF`：不输出开发者可见运行事件。
- `ERRORS`：只输出失败、异常和关键跳过事件。
- `TIMELINE`：输出完整时间线，但只保留摘要元数据。
- `VERBOSE`：在时间线基础上追加截断后的预览字段。

## 当前覆盖范围

本次 change 只覆盖当前最稳定的主链路边界：

- Telegram webhook 接收、忽略不支持 update、重复发送跳过、出站发送开始/成功/失败。
- direct-message ingress 接收、首次处理、已完成幂等命中、并发 in-flight 复用。
- Agent Core 的 run 开始、workspace 加载、recent turns 读取、Skill 解析、模型决策、工具执行、最终回复生成、回复收敛和失败兜底。

运行期观测事件只进入开发者 sink，不会写入 [`ReplyEnvelope`](/D:/QuashyFlies/Project/OpenClaw4J/src/main/java/com/quashy/openclaw4j/domain/ReplyEnvelope.java) 的 `body` 或 `signals`。

## 当前限制

- 目前只有 console sink，没有历史事件查询页，也没有本地持久化。
- `VERBOSE` 只输出截断后的预览字段，不会完整打印 prompt、workspace 文本或原始模型输出。
- 观测模式是进程级配置；当前不支持会话级动态切换。

## 为什么未来可以扩展到 Web UI

当前实现把“事件生产”和“事件消费”分开了：

- 业务边界只通过 `RuntimeObservationPublisher` 发结构化事件。
- 实际输出由 `RuntimeObservationSink` 负责。

这意味着未来接入文件、SQLite 或 Web UI 时，只需要新增 sink 或新的消费链路，而不需要重新修改 Telegram、direct-message 或 Agent Core 的埋点代码。
