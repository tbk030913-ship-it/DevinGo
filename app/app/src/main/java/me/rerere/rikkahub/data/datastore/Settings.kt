package me.rerere.rikkahub.data.datastore

/**
 * Adaptation shim for the RikkaHub-sourced richtext components.
 * Only the display flags those components actually read are modelled;
 * sensible defaults replace their DataStore-backed Settings.
 */
data class Settings(
    val displaySetting: DisplaySetting = DisplaySetting(),
)

data class DisplaySetting(
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = true,
    val codeBlockAutoCollapse: Boolean = true,
    val codeBlockAutoWrap: Boolean = false,
    val showLineNumbers: Boolean = false,
)
