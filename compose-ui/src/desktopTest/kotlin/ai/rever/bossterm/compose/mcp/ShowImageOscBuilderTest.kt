package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.ImageFormat
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `show_image` MCP tool's pure pieces.
 *
 * Covers the OSC 1337 framing emitted by [buildOsc1337Image] (the bytes the tool
 * feeds into the pane's data stream) plus two cross-module contracts the inline-
 * image pipeline relies on: that [DimensionSpec.parse] understands the width/height
 * strings we pass through verbatim, and that [ImageFormat.detect] recognizes the
 * raster headers the emulator's OSC 1337 parser keys on. A running server / PTY
 * isn't needed — these are all pure functions.
 *
 * ESC (0x1B) and BEL (0x07) are asserted via char codes so this source file
 * contains no control characters of its own.
 */
class ShowImageOscBuilderTest {

    private fun decodeB64(value: String): ByteArray =
        Base64.getDecoder().decode(value)

    @Test
    fun oscFramingHasEscBelAndInlineMarker() {
        val payload = "PNGDATA".toByteArray()
        val osc = buildOsc1337Image(payload, name = null, widthSpec = null, heightSpec = null)

        // ESC introducer and BEL terminator.
        assertEquals(27, osc.first().code, "OSC must start with ESC")
        assertEquals(7, osc.last().code, "OSC must end with BEL")

        assertTrue(osc.contains("]1337;File=inline=1"), "missing iTerm2 inline marker: $osc")
        assertTrue(osc.contains(";size=${payload.size}"), "missing/incorrect size: $osc")
        assertTrue(osc.contains(";preserveAspectRatio=1"), "aspect ratio not preserved: $osc")
    }

    @Test
    fun oscBase64RoundTripsToOriginalBytes() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 100, -1, -128, 127)
        val osc = buildOsc1337Image(payload, name = "diagram.png", widthSpec = "80", heightSpec = null)

        // Everything after the single ':' is base64 + the trailing BEL.
        val afterColon = osc.substringAfter(":")
        val b64 = afterColon.substring(0, afterColon.length - 1) // drop BEL
        assertTrue(decodeB64(b64).contentEquals(payload), "base64 did not round-trip to the input bytes")
    }

    @Test
    fun oscEncodesNameAsBase64AndOmitsAbsentDimensions() {
        val osc = buildOsc1337Image("x".toByteArray(), name = "a.png", widthSpec = "80", heightSpec = null)

        val expectedName = Base64.getEncoder().encodeToString("a.png".toByteArray())
        assertTrue(osc.contains(";name=$expectedName"), "name not base64-encoded: $osc")
        assertTrue(osc.contains(";width=80"), "width spec not passed through: $osc")
        assertFalse(osc.contains(";height="), "height should be omitted when null: $osc")
    }

    @Test
    fun oscPassesWidthAndHeightVerbatim() {
        val osc = buildOsc1337Image("x".toByteArray(), name = null, widthSpec = "200px", heightSpec = "50%")
        assertTrue(osc.contains(";width=200px"), "px width not verbatim: $osc")
        assertTrue(osc.contains(";height=50%"), "percent height not verbatim: $osc")
    }

    @Test
    fun dimensionSpecUnderstandsToolWidthHeightForms() {
        // The tool forwards width/height strings untouched; the emulator parses them.
        assertEquals(DimensionSpec.Cells(80), DimensionSpec.parse("80"))
        assertEquals(DimensionSpec.Pixels(200), DimensionSpec.parse("200px"))
        assertEquals(DimensionSpec.Percent(50), DimensionSpec.parse("50%"))
        assertEquals(DimensionSpec.Auto, DimensionSpec.parse("auto"))
        assertEquals(DimensionSpec.Auto, DimensionSpec.parse(null))
    }

    @Test
    fun imageFormatDetectsCommonRasterHeaders() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        val gif = byteArrayOf(
            0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(),
            0x39.toByte(), 0x61.toByte(), 0, 0
        )
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        assertEquals(ImageFormat.PNG, ImageFormat.detect(png))
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(jpeg))
        assertEquals(ImageFormat.GIF, ImageFormat.detect(gif))
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.detect(garbage))
    }
}
