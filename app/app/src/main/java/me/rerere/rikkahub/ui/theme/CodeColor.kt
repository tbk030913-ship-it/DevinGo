package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import me.rerere.bettertext.highlight.HighlightTextColorPalette

// Atom One Dark Theme Colors
val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd), // 紫色
    string = Color(0xFF98c379), // 绿色
    number = Color(0xFFd19a66), // 橙色
    comment = Color(0xFF5c6370), // 灰色
    function = Color(0xFF61afef), // 蓝色
    operator = Color(0xFF56b6c2), // 青色
    punctuation = Color(0xFFabb2bf), // 浅灰色
    className = Color(0xFFe5c07b), // 黄色
    property = Color(0xFFe06c75), // 红色
    boolean = Color(0xFFd19a66), // 橙色
    variable = Color(0xFFe06c75), // 红色
    tag = Color(0xFFe06c75), // 红色
    attrName = Color(0xFFd19a66), // 橙色
    attrValue = Color(0xFF98c379), // 绿色
    fallback = Color(0xFFabb2bf) // 浅灰色
)

// GitHub light theme colors（沿用 AtomOneLightPalette 字段名，值按 GitHub 浅色映射）
val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFCF222E),
    string = Color(0xFF0A3069),
    number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781),
    function = Color(0xFF8250DF),
    operator = Color(0xFF1F2328),
    punctuation = Color(0xFF1F2328),
    className = Color(0xFF953800),
    property = Color(0xFF0550AE),
    boolean = Color(0xFF0550AE),
    variable = Color(0xFF953800),
    tag = Color(0xFF953800),
    attrName = Color(0xFF0550AE),
    attrValue = Color(0xFF0A3069),
    fallback = Color(0xFF1F2328)
)
