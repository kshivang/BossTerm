package ai.rever.bossterm.compose.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

private val uiIconFont by lazy { loadTerminalFont() }

/** Keep the UI typeface for prose; explicitly cover terminal-generated Braille and Nerd Font icons. */
@Composable
internal fun uiTextWithFallback(text: String): AnnotatedString = remember(text) {
    buildAnnotatedString {
        append(text)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val length = Character.charCount(codePoint)
            val fallback = when (codePoint) {
                in 0x2800..0x28FF -> bundledSymbolFont
                in 0xE000..0xF8FF, in 0xF0000..0xFFFFD, in 0x100000..0x10FFFD -> uiIconFont
                else -> null
            }
            if (fallback != null) addStyle(SpanStyle(fontFamily = fallback), offset, offset + length)
            offset += length
        }
    }
}
