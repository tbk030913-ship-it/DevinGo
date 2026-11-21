package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.compositionLocalOf

/** Adaptation shim for RikkaHub's theme local — DevinGo is dark-first. */
val LocalDarkMode = compositionLocalOf { true }
