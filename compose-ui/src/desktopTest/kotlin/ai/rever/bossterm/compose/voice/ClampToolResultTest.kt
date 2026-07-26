package ai.rever.bossterm.compose.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one function both call surfaces depend on for wire safety: neither backend bounds bytes, and
 * an oversized frame fails AFTER the round is settled — so the agent narrates a result the model
 * never received.
 */
class ClampToolResultTest {

    @Test
    fun `a result within the cap is passed through untouched`() {
        val small = """{"lines":["a","b"]}"""
        assertEquals(small, clampToolResult("read_scrollback", small))
    }

    @Test
    fun `an oversized result becomes a self-describing payload`() {
        val huge = """{"data":"${"x".repeat(MAX_TOOL_RESULT_CHARS * 2)}"}"""
        val clamped = clampToolResult("read_scrollback", huge)

        assertTrue(clamped.length < huge.length, "must actually shrink")
        val obj = Json.parseToJsonElement(clamped).jsonObject
        assertEquals(true, obj["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(huge.length, obj["originalLength"]!!.jsonPrimitive.content.toInt())
        // The note is what the agent reads out, so it has to say what to do instead.
        assertTrue(obj["note"]!!.jsonPrimitive.content.contains("ask for less"))
        assertTrue(obj["head"]!!.jsonPrimitive.content.isNotEmpty(), "keep a usable excerpt")
    }

    /**
     * The head is a raw substring, so the cut must not land inside a surrogate pair — a lone
     * surrogate is not valid text and would ride into the model's context.
     */
    @Test
    fun `the excerpt never ends on half a surrogate pair`() {
        // Every character is a 2-char surrogate pair, so a naive take() at an odd index splits one.
        val emoji = "😀".repeat(MAX_TOOL_RESULT_CHARS)
        val clamped = clampToolResult("read_scrollback", emoji)
        val head = Json.parseToJsonElement(clamped).jsonObject["head"]!!.jsonPrimitive.content

        assertFalse(head.isEmpty())
        assertFalse(
            Character.isHighSurrogate(head.last()),
            "head ends on a high surrogate — that's half a character",
        )
        // And it round-trips as text without replacement characters.
        assertFalse(head.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8).contains('�'))
    }

    @Test
    fun `a result exactly at the cap is not truncated`() {
        val exact = "y".repeat(MAX_TOOL_RESULT_CHARS)
        assertEquals(exact, clampToolResult("search_output", exact), "the boundary is inclusive")
    }
}
