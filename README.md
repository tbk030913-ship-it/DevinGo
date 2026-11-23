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
`bridge/` 是会话托管网关：所有 WS 客户端共享**一个常驻 `devin acp`** 进程，hub 按
sessionId 解复用、给每个会话记序号化 journal，手机掉线/杀进程后 attach 回放补齐。

## 目录

| 路径 | 说明 |
|---|---|
| `bridge/` | Go 写的会话托管网关：一个常驻 `devin acp` 托管全部会话 + 配对/推送等 HTTP 端点 |
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
局域网客户端不带 token 访问 `/acp`、`/models` 一律 401；loopback（要求 Host
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

可选离线推送（默认关）：手机进程被杀、前台服务也不在时，审批和回合结束没人看得见。
配一个端点后，bridge 会在「新审批到达且会话无客户端观看」或「回合结束且无观众」时
POST 一条纯文本 `有新审批` / `任务完成`（不带任何会话内容）。任何把 POST body 当消息
文本的 webhook 都能用；[ntfy](https://ntfy.sh) 是测试过的路径——手机上装 ntfy App
订阅同一个 topic 即可：

```bash
./devingo-bridge.exe -push "https://ntfy.sh/my-secret-topic"
# 也可 DEVINGO_PUSH_URL 环境变量，或写进 %APPDATA%\DevinGo\bridge.json 的 "push_url"
```

用 `-install` 跑计划任务的建议走 `bridge.json` 的 `push_url`（`-push` 这类 flag
不会带进 schtasks）。推送是 fire-and-forget，失败只记日志，不影响 hub。

启动日志会打印局域网 `ws://<ip>:8790/acp` 地址和配对二维码（payload 同 /pair）。

- `GET /healthz` → bridge 状态 + devin 是否可用 + 默认 cwd（配对探测用，免鉴权）
- `GET /models`  → 缓存的模型列表（启动后异步 + 每 6h 跑一次 `devin models list`，只留 id/name）
- `GET /pair`    → 配对二维码网页（只有本机打开才带 token）
- `POST /fs/write` → 在会话工作区内写文件（`{sessionId,path,content}`，≤1MiB，越界 403）
- `WS  /acp`     → ACP 会话托管（标准 ACP + `_devingo/*` 私有扩展，见下）

行为要点：单条 WS 消息上限 4MiB（默认 32KiB 会断大 payload）；最多 8 个并发 `/acp`
连接（共享同一个常驻 devin），超出返回 503；常驻 `devin acp` 退出/崩溃时在途请求报错、
hub 广播 `_devingo/agent_dead` 并按需懒重拉进程，会话本体在 devin 侧持久化、
`session/load` 复活；每 25s ping、10s pong 超时，用来发现手机睡死留下的半开连接；
会话无订阅/无在途请求/无待批满 `-idle-ttl`（默认 10min）后 hub 回收；`/models` 的
502 `error` 字段会附带 devin stderr 的前 2KiB。

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

正常情况下**无感**：常驻 `devin acp` 起来后，bridge 会自己完成 `authenticate`——把
`%APPDATA%\devin\credentials.toml` 里的 `windsurf_api_key` 注入 ACP 握手
（`-devinkey`/`DEVIN_API_KEY` 可覆盖），App 拿到的 initialize 应答就是已登录状态。

凭据缺失或注入失败时退回浏览器登录：App 发起 `authenticate`（`devin-browser`），电脑上
弹出浏览器完成一次 Devin 登录即可。凭据之后由 devin 侧持久化，重连不用再登。

## 会话托管机制（hub）

一个常驻 `devin acp` 进程承载所有客户端的所有会话。网关代办 `initialize`/`authenticate`
（应答缓存直接回客户端，客户端握手零等待），其余请求 id 重写到网关内部序列再转发、响应
映射回来——普通 ACP 客户端无感，可以直接连。每个会话的事件进 5000 条 / 8MiB 的序号化
环形 journal（帧上带 `_seq`）：掉线、杀进程、换设备都不丢回合，重连后 `_devingo/attach`
按游标补放缺口，journal 已截断则回退 `session/load`。审批由 hub 持有（`-perm-ttl` 默认
30min），同会话多客户端广播、首个应答获胜、其余收到 `permission_resolved`。无订阅、
无在途、无待批的会话超过 `-idle-ttl`（默认 10min）被 hub 释放（`session/close`），
下次打开再 `session/load` 拉起。

## `_devingo/*` 私有方法

`_devingo/` 前缀的请求由网关自己应答，永不转发给 devin：

| 方法 / 通知 | 说明 |
|---|---|
| `_devingo/hello` | 客户端报到；回 clientId、版本、能力集 |
| `_devingo/sessions` | `session/list` 合并 hub 实时态（`running`、`pendingPermission`）；会话列表只走它，HTTP `/sessions` 已移除 |
| `_devingo/attach` | 订阅会话 + `afterSeq` 之后 journal 回放；回 `lastSeq`/`running`/`pendingPermission`，截断或未托管返 `needLoad` |
| `_devingo/detach` | 退订；hub 继续托管该会话 |
| `_devingo/event`（通知） | 别的客户端请求产生的回合结果（`session/prompt` 应答） |
| `_devingo/permission_resolved`（通知） | 审批被其他客户端/TTL 解决，`outcome` 附结果 |
| `_devingo/session_resync`（通知） | 他人的 `session/load` 回放期本客户端漏了 live 帧——按 `lastSeq` 重新 attach |
| `_devingo/session_deleted`（通知） | 会话被删除 |
| `_devingo/agent_dead`（通知） | 常驻 devin 进程死亡，running/待批已复位 |

## App 已实现的协议面

- `initialize` / `authenticate` / `session/new` / `session/load` / `session/prompt` /
  `session/cancel` / `session/delete` / `session/close`
- `session/set_config_option`（mode / model / effort 等统一入口）、`session/list`、
  `_cognition.ai/session/rename`
- `session/update` 通知：agent 回复流式追加、思考流、tool_call(+update)、plan、
  mode/model 变更、token 用量、上下文压缩
- `session/request_permission` → App 内审批卡 + 系统通知（锁屏可直接允许/拒绝）
- Devin 私有扩展：`_cognition.ai/revert/*`（回退步骤列表 + 分叉）、`_cognition.ai/output`
  （warn/error 级日志）、`available_commands_update`（斜杠命令）
- 未知 agent→client 请求（如 `fs/*`、`terminal/*`）统一回 `method_not_found` —— Devin 会自行完成文件/终端操作

## 安全注意

- bridge 监听的是**明文 ws://**，App 声明了 `usesCleartextTraffic`。请只在可信局域网用，
  公网暴露务必加 `-token` + 前面套一层 TLS（如 Caddy/nginx wss 反代，或 Tailscale）。
- 带非 localhost `Origin` 的浏览器请求会被拒绝（/acp 防 CSWSH，/models
  防 drive-by 探测）；无 Origin 的原生客户端不受影响。loopback 免鉴权同时
  要求 `Host` 也是 localhost/127.0.0.1，堵住 DNS 重绑定借浏览器读数据的口子。
- App 里保存的 token 经 AndroidKeyStore AES/GCM 加密后落 SharedPreferences，
  仍建议把它当局域网共享密钥看待，别和重要凭据混用。
- Devin 的权限模式决定 Agent 能干什么：Normal/Accept-Edits 模式下每次危险操作都会
  弹审批到手机上；想要无人值守可以建会话后在模式菜单里切 `bypass`（慎用）。
- Windows 上 Devin 没有 sandbox（autonomous 模式不可用）。

## 已知限制 / TODO

- 回退只接了「列表 + 分叉成新会话」：`revert/preview`、`revert/execute`（在本会话内回滚）
  协议常量已留但未接线；agent 不支持 revert 时菜单项自动隐藏
- 附件只支持图片（压缩后 ≤3MB/条）；`@` 引用靠文本路径推断，没有文件选择器
- `/models` 只留 id + name（无描述/参数）；模型列表主要靠会话内 `configOptions`，
  `/models` 是兜底
- `-install` 的计划任务是 Windows schtasks（登录时启动，非系统服务）——未登录的
  Windows 会话里 bridge 不跑
- `/fs/write` 只写会话 cwd 之内（相对路径 + 符号链接/硬链接不越界）；没有配套的
  `/fs/list` 文件选择接口，引用芯片只作用于已回复的消息
