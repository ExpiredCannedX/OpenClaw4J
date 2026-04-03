# Telegram 单聊适配器接入说明

## 目标

本文档说明如何为当前仓库启用 Telegram 私聊文本 adapter，并完成从 webhook 入站到 `sendMessage` 出站的最小真实联调闭环。

当前实现边界：

- 只支持 Telegram 私聊文本消息。
- 只发送最终一次性纯文本回复。
- 不支持群聊、频道、媒体消息、按钮回调和流式输出。
- 不会在应用启动时自动调用 Telegram `setWebhook`，由开发者或运维手动注册。

## 1. 创建 Telegram Bot

1. 在 Telegram 中找到 `@BotFather`。
2. 发送 `/newbot`，按提示创建 bot。
3. 保存 BotFather 返回的 bot token，后续用于 `TELEGRAM_BOT_TOKEN`。
4. 用一个真实 Telegram 用户账号先给 bot 发送 `/start` 或任意文本消息，否则 bot 无法主动发起私聊。

## 2. 配置环境变量

运行应用前至少准备以下环境变量：

```powershell
$env:OPENCLAW_TELEGRAM_ENABLED="true"
$env:TELEGRAM_BOT_TOKEN="<your-bot-token>"
$env:TELEGRAM_WEBHOOK_SECRET="<your-webhook-secret>"
$env:TELEGRAM_WEBHOOK_PATH="/api/telegram/webhook"
$env:TELEGRAM_WEBHOOK_URL="https://<public-host>/api/telegram/webhook"
```

说明：

- `OPENCLAW_TELEGRAM_ENABLED`：控制 Telegram adapter 是否装配。
- `TELEGRAM_BOT_TOKEN`：BotFather 生成的 bot token。
- `TELEGRAM_WEBHOOK_SECRET`：Telegram webhook 回调头 `X-Telegram-Bot-Api-Secret-Token` 的共享密钥。
- `TELEGRAM_WEBHOOK_PATH`：应用内部暴露的 webhook 路径，默认是 `/api/telegram/webhook`。
- `TELEGRAM_WEBHOOK_URL`：Telegram 能访问到的公网 HTTPS 地址，必须与实际回调地址一致。

如果 adapter 未启用或关键配置缺失，应用仍可启动，但 Telegram webhook 无法形成有效闭环。

## 3. 启动应用

在仓库根目录执行：

```powershell
mvn spring-boot:run
```

开发用 HTTP 调试入口 `/api/direct-messages` 仍然保留，可以和 Telegram adapter 并存，用于非 Telegram 场景的本地快速验证。

## 4. 手动注册 Telegram Webhook

将 `<bot-token>`、`<public-webhook-url>` 和 `<secret-token>` 替换为你的实际值：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "https://api.telegram.org/bot<bot-token>/setWebhook" `
  -ContentType "application/json" `
  -Body '{
    "url": "<public-webhook-url>",
    "secret_token": "<secret-token>"
  }'
```

若需要回滚 Telegram adapter，可删除 webhook：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "https://api.telegram.org/bot<bot-token>/deleteWebhook"
```

## 5. 本地调试约束

Telegram webhook 需要公网可访问的 HTTPS 地址，不能直接把 `localhost` 注册给 Telegram。常见做法：

- 使用 `ngrok`、`cloudflared tunnel` 或其他隧道工具暴露本地服务。
- 把 `TELEGRAM_WEBHOOK_URL` 指向隧道生成的公网 HTTPS 地址。
- 确保该公网地址最终会转发到本地应用的 `TELEGRAM_WEBHOOK_PATH`。

建议本地联调时先做这几步：

1. 启动应用并确认 `/api/telegram/webhook` 已对外可达。
2. 调用 `setWebhook` 注册公网地址和 secret token。
3. 用个人 Telegram 账号给 bot 发送一条私聊文本。
4. 观察应用日志，确认 webhook 已进入统一单聊主链路。

## 6. 运行期行为说明

当前 Telegram adapter 的固定行为如下：

- 只有 `message.chat.type = private` 且 `message.text` 非空时，才会进入统一单聊主链路。
- `from.id` 会映射为统一外部用户 ID。
- `chat.id` 会映射为统一外部会话 ID，并作为回复目标。
- `update_id` 会映射为统一外部消息 ID，用于幂等去重。
- Telegram 出站失败时只记录基础日志，不会自动重试或自动切换其他发送路径。

## 7. 已知限制

- 当前仓储实现仍是内存版，应用重启后用户映射、活跃会话和已处理消息记录都会丢失。
- 当前未实现 webhook 自动注册、健康检查或出站补偿队列。
- 如果 Telegram 重复投递同一个 update，而首次发送结果已不可观测，当前实现只能提供“核心链路幂等”，不能提供持久化级别的出站去重保证。
