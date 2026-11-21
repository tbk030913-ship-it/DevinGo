# DevinGo

用手机（Android）操作本地 Devin CLI。

```
Android App (Kotlin + Compose)
     ⇅  WebSocket —— ACP JSON-RPC，一条消息一个 text frame
bridge/ (Go, devingo-bridge.exe)
     ⇅  stdio —— ACP 的 NDJSON，一行一条消息
  devin acp（本机 Devin CLI 内置的 ACP 服务器）
```

`devin acp` 是 Devin CLI 自带的 [Agent Client Protocol](https://agentclientprotocol.com/)
服务器（Zed / JetBrains 集成 Devin 用的同一套协议）。它只讲 stdio，所以手机上无法直连——
`bridge/` 是一个纯管道：每条 WebSocket text frame ↔ devin stdin/stdout 的一行 JSON-RPC。

## 目录

| 路径 | 说明 |
|---|---|
| `bridge/` | Go 写的 WS↔stdio 中继。每个 WS 连接 spawn 一个 `devin acp` 进程 |
| `app/`    | Android 工程（Kotlin + Compose + OkHttp + kotlinx.serialization） |

## 快速开始

### 1. 启动 bridge（在跑 Devin 的电脑上）

```bash
cd bridge
go build -o devingo-bridge.exe .
./devingo-bridge.exe -token "s3cret-long-random"
```

默认 `-addr :8790` 绑定所有网卡，此时**必须**设置 `-token`（或 `DEVINGO_TOKEN` 环境变量），
否则 bridge 拒绝启动——没有鉴权时局域网内任何人都能驱动你的 Devin。本机独占测试可以用
`-addr 127.0.0.1:8790`（loopback 不强制 token）；确实要免密暴露时显式加 `-insecure`。

常用参数：

```bash
./devingo-bridge.exe \
  -addr 0.0.0.0:8790 \               # 监听地址
  -devin "C:\...\devin.exe" \        # devin 不在 PATH 时指定绝对路径
  -cwd "E:\project\my\DevinGo" \     # spawn devin 的工作目录（App 里也可覆盖）
  -token "s3cret" \                  # 要求客户端带 Bearer token（也可 DEVINGO_TOKEN）
  -insecure                          # 显式允许非 loopback 免 token（不推荐）
```

启动日志会打印局域网 `ws://<ip>:8790/acp` 地址。

- `GET /healthz` → bridge 状态 + devin 是否可用 + 默认 cwd
- `WS  /acp`     → ACP 中继

行为要点：单条 WS 消息上限 4MiB（默认 32KiB 会断大 payload）；最多 8 个并发 `/acp`
会话（每个都 spawn 一个 `devin acp`），超出返回 503；`devin acp` 自行退出/崩溃时
WS 以 close 1011 + 退出原因关闭（正常结束仍是 1000）；每 25s ping、10s pong 超时，
用来发现手机睡死留下的半开连接并杀掉对应 devin；`/sessions`、`/models` 的 502
`error` 字段会附带 devin stderr 的前 2KiB。

### 2. 安装并连接 App

```bash
cd app
./gradlew :app:installDebug          # USB 连接手机，或先 assembleDebug 再手动装
```

App 里填：

- **Bridge 地址**：`192.168.x.x:8790`（bridge 日志里打印的那个）
- **Token**：bridge 用 `-token` 启动时才需要
- **工作目录**：Devin 要在哪个目录干活（Windows 路径，留空 = bridge 的 cwd）

### 3. 首次登录

这个版本的 `devin acp` **不复用** `devin auth login` 的本地凭据——ACP host 是唯一凭据来源。
所以首次建会话会返回 `auth_required`，App 会自动发起 `authenticate`（`devin-browser`），
此时**电脑上会弹出浏览器，完成一次 Devin 登录即可**。之后凭据在 PC 侧持久化，

重连一般不用再登。

## App 已实现的协议面

- `initialize` / `authenticate` / `session/new` / `session/prompt` / `session/cancel`
- `session/set_mode`、`session/set_model`
- `session/update` 通知：agent 回复流式追加、思考流、tool_call(+update)、plan、mode 变更
- `session/request_permission` → App 弹审批框（allow / reject 按钮由 Devin 给的 options 决定）
- Devin 私有扩展 `_cognition.ai/output`：只显示 warn/error 级日志
- 未知 agent→client 请求（如 `fs/*`、`terminal/*`）统一回 `method_not_found` —— Devin 会自行完成文件/终端操作

## 安全注意

- bridge 监听的是**明文 ws://**，App 声明了 `usesCleartextTraffic`。请只在可信局域网用，
  公网暴露务必加 `-token` + 前面套一层 TLS（如 Caddy/nginx wss 反代，或 Tailscale）。
- bridge 拒绝带非 localhost Origin 的浏览器 WebSocket 连接（防 CSWSH/DNS rebinding），
  原生 App 不受影响；绑非 loopback 地址且未设 token 时会拒绝启动（`-insecure` 可跳过）。
- App 里保存的 token 是**明文 SharedPreferences**（离线依赖里没有
  EncryptedSharedPreferences）——请用可丢弃的局域网 token，别用重要凭据。
- Devin 的权限模式决定 Agent 能干什么：Normal/Accept-Edits 模式下每次危险操作都会
  弹审批到手机上；想要无人值守可以建会话后在模式菜单里切 `bypass`（慎用）。
- Windows 上 Devin 没有 sandbox（autonomous 模式不可用）。

## 已知限制 / TODO

- 只实现了 `session/new`；`session/load`（恢复历史会话）协议上支持（`loadSession: true`）但 UI 还没做
- 图片/文件附件、`@` 引用、斜杠命令面板：未实现（`available_commands_update` 已在协议层收到）
- Devin 私有 `_meta` 能力（sessionList、rename、share 等）未接线
- 断线自动重连、后台保活：未实现，App 退后台 WS 会断
