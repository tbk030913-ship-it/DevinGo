# 内置 Tailscale（tsnet）原生库

让手机不装 Tailscale App 也能接入 tailnet：Go 的 `tsnet` 节点以内嵌 c-shared 库（`libdevingotailnet.so`）形式运行在应用进程里，JNI 符号直接导出给 `com.devingo.app.tailnet.TailnetNative`，无需 CMake 或 C 胶水层。

## 前置条件

- Git、`go`（会自动下载 `go1.25.5` 工具链）、Android NDK `28.2.13676358`（默认找 `%LOCALAPPDATA%\Android\Sdk\ndk`，或传 `-AndroidSdk`）。
- 首次构建需要联网下载 `tailscale.com v1.94.1` 模块及依赖。

## 一条命令

```powershell
pwsh app/tailnet/build-native.ps1
```

产出 `app/app/src/main/jniLibs/arm64-v8a/libdevingotailnet.so` 与 `x86_64/libdevingotailnet.so`，并校验 5 个 JNI 导出符号、打印 SHA-256。

## CGNAT 补丁的作用

`cgnat-netstack.patch` 修改 `tsnet.Server` 的路由判定：凡是目的地址落在 `100.64.0.0/10` 的连接一律强制走 netstack 用户态网络。没有这条补丁时，节点刚启动、peer 路由尚未下发的窗口期内，`s.Dial` 会把 100.x 地址回退到手机系统网络直连，表现为连接失败；补丁保证了"非 tailnet 地址永远不偷偷走系统出口"这一不变量。

## 源码来源

- `tailscale.com v1.94.1`（构建时下载并校验模块哈希，再打补丁）—— BSD-3-Clause
- `go/android_interfaces.go` 原样复制自 `velzosoft/libtailscale`（`6d4344a`）：Android 11+ 禁止应用用 netlink `RTM_GETLINK` 枚举网卡，改用 bionic `getifaddrs` 给 netmon 提供接口列表 —— BSD-3-Clause

许可证文本见仓库根目录 `LICENSES/` 与 `THIRD_PARTY_NOTICES.md`。
