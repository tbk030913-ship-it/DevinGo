package me.rerere.rikkahub

/**
 * Adaptation shim: only the route the ported richtext components can try
 * to navigate to. The shimmed [Navigator] no-ops it — Mermaid/code previews
 * stay inline in DevinGo.
 */
sealed interface Screen {
    data class WebView(val contentId: String) : Screen
}
