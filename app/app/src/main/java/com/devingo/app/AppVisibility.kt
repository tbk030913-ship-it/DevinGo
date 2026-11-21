package com.devingo.app

/**
 * Foreground/background flag for the app process, maintained by
 * MainActivity's onStart/onStop. Notification gating reads it at post time
 * so "仅在后台时提醒" works even for events raised while composing.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false

    /** True while the chat screen is composed; alert suppression reads it. */
    @Volatile
    var chatVisible: Boolean = false
}
