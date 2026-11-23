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

    /**
     * The session currently on screen — a permission alert for a DIFFERENT
     * session must still notify (the user isn't looking at it).
     */
    @Volatile
    var chatSessionId: String? = null
}
