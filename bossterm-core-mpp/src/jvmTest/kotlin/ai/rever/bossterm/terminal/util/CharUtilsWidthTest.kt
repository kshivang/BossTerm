package ai.rever.bossterm.terminal.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharUtilsWidthTest {

    @Test
    fun supplementaryNerdFontIconsAreSingleWidthByDefault() {
        val yaziIcons = listOf(0xF0331, 0xF021B, 0xF0219)

        yaziIcons.forEach { codePoint ->
            val icon = String(Character.toChars(codePoint))
            assertEquals(1, CharUtils.mk_wcwidth(codePoint, ambiguousIsDoubleWidth = false))
            assertFalse(CharUtils.isDoubleWidthCharacter(codePoint, ambiguousIsDWC = false))
            assertEquals(1, GraphemeUtils.getGraphemeWidth(icon, ambiguousIsDWC = false))
        }
    }

    @Test
    fun supplementaryPrivateUseIconsHonorAmbiguousWidthSetting() {
        assertEquals(2, CharUtils.mk_wcwidth(0xF0331, ambiguousIsDoubleWidth = true))
        assertTrue(CharUtils.isDoubleWidthCharacter(0xF0331, ambiguousIsDWC = true))
        assertEquals(
            2,
            GraphemeUtils.getGraphemeWidth(
                String(Character.toChars(0xF0331)),
                ambiguousIsDWC = true
            )
        )
        assertEquals(2, CharUtils.mk_wcwidth(0x100000, ambiguousIsDoubleWidth = true))
    }
}
