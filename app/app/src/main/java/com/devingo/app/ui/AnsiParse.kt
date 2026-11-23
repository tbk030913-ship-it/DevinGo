package com.devingo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

// Lightweight ANSI → AnnotatedString for command output, ported from t3code
// (MIT): .research/t3code-20261123/packages/shared/src/terminalOutput.ts
//   (TERMINAL_PALETTES.light, terminalOutputSpans — the 16-color slice).
// Scope per U5.3: SGR fg colors 30-37 + 90-97, bold (1), reset (0); every
// other escape sequence is stripped.

private const val ESC = '\u001b'

/** t3code TERMINAL_PALETTES.light — normal 0-7, bright 8-15. */
private val AnsiPalette = listOf(
    Color(0xFF24292F), Color(0xFFCF222E), Color(0xFF116329), Color(0xFF7D4E00),
    Color(0xFF0550AE), Color(0xFF8250DF), Color(0xFF1B7C83), Color(0xFF6E7781),
    Color(0xFF57606A), Color(0xFFA40E26), Color(0xFF1A7F37), Color(0xFF633C01),
    Color(0xFF0969DA), Color(0xFF6639BA), Color(0xFF3192AA), Color(0xFF8C959F),
)

/** SGR sequences: `ESC [ <params> m`. */
private val Sgr = Regex("$ESC\\[([0-9;]*)m")

/**
 * Every other escape shape the terminal may emit: non-SGR CSI, OSC
 * (`ESC ] … BEL/ST`), charset designate and plain two-byte escapes.
 * These are stripped from visible text, never styled.
 */
private val OtherEscape = Regex(
    "$ESC\\[[0-?]*[ -/]*[@-~]|" +              // CSI: params 0x30-0x3F, intermediates 0x20-0x2F, final 0x40-0x7E
        "$ESC][^\u0007$ESC]*(?:\u0007|$ESC\\\\)|" + // OSC, BEL- or ST-ended
        "$ESC[PX^_][^$ESC]*(?:$ESC\\\\)|" +       // DCS/SOS/PM/APC payload up to ST
        "$ESC[()][0-9A-B]|" +                  // charset designate
        "$ESC[@-_]",                            // two-byte escapes (ESC + 0x40-0x5F)
)

/** A leading fragment left when a window cap sliced through an escape. */
internal val LEADING_ESC_FRAGMENT =
    Regex("^\\[?[0-9;?]*[ -/]*[@-~]|^[0-9;]*[A-Za-z~]")

/**
 * `\r` overwrites the current terminal line — for each line keep only the
 * tail past its last carriage return (`\r\n` is a newline, normalized first).
 */
internal fun foldCarriageReturn(text: String): String =
    text.replace("\r\n", "\n").split('\n')
        .joinToString("\n") { it.substringAfterLast('\r') }

/** Plain-text form: all ANSI gone (clipboard copy). */
internal fun stripAnsi(text: String): String =
    OtherEscape.replace(Sgr.replace(text, ""), "")

/**
 * SGR-styled [AnnotatedString]: 30-37/90-97 pick from [AnsiPalette], 1 sets
 * bold, 0/39/22 clear; 38/48 extended-color args are consumed so their
 * values can't be misread as plain SGR codes.
 */
internal fun ansiToAnnotatedString(text: String): AnnotatedString =
    buildAnnotatedString {
        var fg: Color? = null
        var bold = false
        var pos = 0
        fun emit(to: Int) {
            if (to <= pos) return
            val segment = OtherEscape.replace(text.substring(pos, to), "")
            if (segment.isEmpty()) return
            val color = fg
            if (color == null && !bold) {
                append(segment)
            } else {
                withStyle(
                    SpanStyle(
                        color = color ?: Color.Unspecified,
                        fontWeight = if (bold) FontWeight.SemiBold else null,
                    ),
                ) {
                    append(segment)
                }
            }
        }
        for (m in Sgr.findAll(text)) {
            emit(m.range.first)
            val parts = m.groupValues[1].split(';')
            var j = 0
            while (j < parts.size) {
                when (val n = parts[j].toIntOrNull() ?: 0) {
                    0 -> { fg = null; bold = false }
                    1 -> bold = true
                    22 -> bold = false
                    39 -> fg = null
                    in 30..37 -> fg = AnsiPalette[n - 30]
                    in 90..97 -> fg = AnsiPalette[n - 90 + 8]
                    38, 48 -> {
                        // 38;5;N / 38;2;R;G;B — skip the args, keep no color.
                        j += when (parts.getOrNull(j + 1)?.toIntOrNull()) {
                            5 -> 2
                            2 -> 4
                            else -> 0
                        }
                    }
                }
                j++
            }
            pos = m.range.last + 1
        }
        emit(text.length)
    }
