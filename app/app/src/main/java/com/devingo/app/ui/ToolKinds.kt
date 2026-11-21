package com.devingo.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Move
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Terminal
import me.rerere.hugeicons.stroke.Wrench01

/**
 * Tool-call kind → icon/label lookups shared by the transcript timeline,
 * permission sheet and tool-group rows. Kinds arrive verbatim from ACP
 * `tool_call` updates; anything unrecognized falls back to a generic wrench.
 */
fun toolKindIcon(kind: String?): ImageVector = when (kind) {
    "execute" -> HugeIcons.Terminal
    "read" -> HugeIcons.FileView
    "edit" -> HugeIcons.PencilEdit01
    "delete" -> HugeIcons.Delete02
    "move" -> HugeIcons.Move
    "search" -> HugeIcons.Search01
    "fetch" -> HugeIcons.Globe02
    "think" -> HugeIcons.Brain
    else -> HugeIcons.Wrench01
}

/** Short Chinese label — used for node content descriptions. */
fun toolKindLabel(kind: String?): String = when (kind) {
    "execute" -> "运行命令"
    "read" -> "读取文件"
    "edit" -> "编辑文件"
    "delete" -> "删除文件"
    "move" -> "移动文件"
    "search" -> "搜索"
    "fetch" -> "抓取网页"
    "think" -> "思考"
    else -> "工具步骤"
}
