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
./devingo-bridge.exe
```

默认 `-addr :8790` 绑定所有网卡。**Token 不用手填**：首次启动自动生成 32 字节随机串，
存到 `%APPDATA%\DevinGo\bridge.json`，之后复用；`-token`/`DEVINGO_TOKEN` 可显式覆盖。
局域网客户端不带 token 访问 `/acp`、`/sessions`、`/models` 一律 401；loopback（要求 Host
也是 localhost）与 `/healthz`、`/pair` 放行，方便本机工具与配对探测。`-insecure` 显式关闭
远端鉴权（启动打醒目警告，不推荐）；和 `-token` 同用时 token 仍然生效。

常用参数：

```bash
./devingo-bridge.exe \
  -addr 0.0.0.0:8790 \               # 监听地址
  -devin "C:\...\devin.exe" \        # devin 不在 PATH 时指定绝对路径
  -cwd "E:\project\my\DevinGo" \     # spawn devin 的工作目录（App 里也可覆盖）
  -token "s3cret" \                  # 覆盖自动生成的 Bearer token（也可 DEVINGO_TOKEN）
  -insecure                          # 显式关闭远端鉴权（不推荐）
```

开机自启（注册"当前用户登录时启动"的计划任务，登录凭据在用户目录下、不做系统服务）：

```bash
./devingo-bridge.exe -install        # schtasks 注册；任务以安装时的 cwd 作为 -cwd
./devingo-bridge.exe -uninstall      # 移除；任务不存在也算成功
```

启动日志会打印局域网 `ws://<ip>:8790/acp` 地址和配对二维码（payload 同 /pair）。

- `GET /healthz` → bridge 状态 + devin 是否可用 + 默认 cwd（配对探测用，免鉴权）
- `GET /sessions`→ `devin list --format json` 历史会话列表
- `GET /models`  → `devin models list --format json`
- `GET /pair`    → 配对二维码网页（只有本机打开才带 token）
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

打开 App 左侧二维码图标，扫 bridge 启动时终端打印的配对码（或在电脑浏览器开
`http://localhost:8790/pair`）。配对码自带候选地址列表和 token，App 探测到第一个
可用地址后自动填入并保存。从其它局域网设备打开 `/pair` 页拿到的二维码**不含 token**
——token 只出现在操作者本机屏幕上。

手动填也可以：

- **Bridge 地址**：`192.168.x.x:8790`（bridge 日志里打印的那个）
- **Token**：远端连接必填，扫码会自动带上；也可看 `%APPDATA%\DevinGo\bridge.json`
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
- 带非 localhost `Origin` 的浏览器请求会被拒绝（/acp 防 CSWSH，/sessions、/models
  防 drive-by 刷 `devin list`）；无 Origin 的原生客户端不受影响。loopback 免鉴权同时
  要求 `Host` 也是 localhost/127.0.0.1，堵住 DNS 重绑定借浏览器读数据的口子。
- App 里保存的 token 经 AndroidKeyStore AES/GCM 加密后落 SharedPreferences，
  仍建议把它当局域网共享密钥看待，别和重要凭据混用。
- Devin 的权限模式决定 Agent 能干什么：Normal/Accept-Edits 模式下每次危险操作都会
  弹审批到手机上；想要无人值守可以建会话后在模式菜单里切 `bypass`（慎用）。
- Windows 上 Devin 没有 sandbox（autonomous 模式不可用）。

## 已知限制 / TODO

- 只实现了 `session/new`；`session/load`（恢复历史会话）协议上支持（`loadSession: true`）但 UI 还没做
- 图片/文件附件、`@` 引用、斜杠命令面板：未实现（`available_commands_update` 已在协议层收到）
- Devin 私有 `_meta` 能力（sessionList、rename、share 等）未接线
- 断线自动重连、后台保活：未实现，App 退后台 WS 会断
