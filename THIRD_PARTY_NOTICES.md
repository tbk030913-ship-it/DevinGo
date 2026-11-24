# Third-Party Notices

## tailscale.com (tsnet) — v1.94.1

- Source: <https://github.com/tailscale/tailscale>, Go module `tailscale.com v1.94.1`
  (module sum `h1:0dAst/ozTuFkgmxZULc3oNwR9+qPIt5ucvzH7kaM0Jw=`, verified at build time).
- Use: compiled into `libdevingotailnet.so` (see `app/tailnet/`), with the local
  `app/tailnet/cgnat-netstack.patch` applied to `tsnet/tsnet.go`.
- License: BSD-3-Clause — `LICENSES/Tailscale-BSD-3-Clause.txt`.

## libtailscale Dart package (android_interfaces.go)

- Source: <https://github.com/velzosoft/libtailscale>, commit
  `6d4344a3498e1f214b341436b04816592975016d`, `lib/src/hook/android_interfaces.go`.
- Use: copied unmodified to `app/tailnet/go/android_interfaces.go`; registers a
  getifaddrs-based interface getter for netmon on Android.
- License: BSD-3-Clause — `LICENSES/libtailscale-Dart-BSD-3-Clause.txt`.

## RikkaHub richtext components

- Source: `me.rerere.*` package under `app/app/src/main/java/me/rerere/`,
  ported from RikkaHub (AGPL-3.0). See upstream project for license text.

## t3code mobile modules (T3 Tools Inc.)

- Source: <https://github.com/pingdotgg/t3code>, snapshot cloned at
  `.research/t3code-20261123/`.
- Use: Kotlin/Compose ports of `apps/mobile/modules/t3-review-diff`
  (`app/.../ui/diff/`), `t3-composer-editor` (`ui/ComposerEditor.kt`),
  `t3-agent-notifications` (`ui/notification/`), `t3-terminal`
  (`ui/terminal/` — the char-grid frame protocol is also re-implemented
  server-side in `bridge/pty.go`) and `t3-preview-tunnel` (`bridge/proxy.go`).
- License: MIT — `LICENSES/t3code-MIT.txt`.
