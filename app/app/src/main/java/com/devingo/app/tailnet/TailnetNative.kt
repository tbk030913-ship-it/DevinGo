package com.devingo.app.tailnet

/**
 * JNI surface of libdevingotailnet.so (the Go tsnet library in app/tailnet/go).
 * Loading is best-effort: on ABIs without a bundled .so, [loaded] stays false
 * and the manager reports Unsupported instead of crashing at class init.
 */
object TailnetNative {
    val loaded: Boolean = runCatching { System.loadLibrary("devingotailnet") }.isSuccess

    /** Returns "" on success (idempotent), otherwise the native error text. */
    external fun start(stateDir: String, cacheDir: String, hostname: String): String

    /** JSON: state/authUrl/ips/dnsName/tailnet/error — see tailnet-spec §N. */
    external fun status(): String

    /** Loopback forwarder to a tailnet "host:port"; returns the port or -1. */
    external fun forward(target: String): Int

    external fun stop()
    external fun logout()
}
