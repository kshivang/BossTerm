package ai.rever.bossterm.compose.mcp

import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.ImageDimensionCalculator
import ai.rever.bossterm.terminal.model.image.TerminalImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ImageDimensionCalculator], the pure sizing function behind
 * inline images (`show_image` / OSC 1337).
 *
 * Regression coverage for issue #323: the Auto+Auto branch used to clamp only
 * width overflow, so a landscape image narrower than the pane but taller than
 * it rendered at full intrinsic size and clipped vertically. Both dimensions
 * must now be fitted by the most-constrained axis.
 */
class ImageDimensionCalculatorTest {

    // 100 cols x 20 rows at 10x20px cells -> 1000x400 px pane
    // (a wide-but-short pane, like a bottom horizontal split).
    private val cols = 100
    private val rows = 20
    private val cellW = 10f
    private val cellH = 20f
    private val paneWidthPx = cols * cellW
    private val paneHeightPx = rows * cellH

    private fun autoImage(intrinsicWidth: Int, intrinsicHeight: Int) = TerminalImage(
        data = ByteArray(1),
        intrinsicWidth = intrinsicWidth,
        intrinsicHeight = intrinsicHeight,
        widthSpec = DimensionSpec.Auto,
        heightSpec = DimensionSpec.Auto,
        preserveAspectRatio = true
    )

    private fun calculate(image: TerminalImage) = ImageDimensionCalculator.calculate(
        image = image,
        terminalWidthCells = cols,
        terminalHeightCells = rows,
        cellWidthPx = cellW,
        cellHeightPx = cellH
    )

    private fun assertAspectPreserved(image: TerminalImage, pixelWidth: Int, pixelHeight: Int) {
        val intrinsic = image.intrinsicWidth.toFloat() / image.intrinsicHeight
        val actual = pixelWidth.toFloat() / pixelHeight
        assertTrue(
            abs(intrinsic - actual) / intrinsic < 0.02f,
            "aspect ratio drifted: intrinsic=$intrinsic actual=$actual"
        )
    }

    @Test
    fun autoAutoKeepsIntrinsicSizeWhenItFits() {
        val dims = calculate(autoImage(500, 300))
        assertEquals(500, dims.pixelWidth)
        assertEquals(300, dims.pixelHeight)
    }

    @Test
    fun autoAutoScalesDownWidthOverflow() {
        val image = autoImage(2000, 300)
        val dims = calculate(image)
        assertTrue(dims.pixelWidth <= paneWidthPx.toInt(), "width still overflows: ${dims.pixelWidth}")
        assertTrue(dims.pixelHeight <= paneHeightPx.toInt(), "height overflows: ${dims.pixelHeight}")
        assertAspectPreserved(image, dims.pixelWidth, dims.pixelHeight)
    }

    @Test
    fun autoAutoScalesDownHeightOverflow() {
        // Issue #323: 960x560 landscape image in a 1000x400px pane. Width fits,
        // height does not — the old code rendered it 960x560 and clipped the
        // bottom 160px past the pane boundary.
        val image = autoImage(960, 560)
        val dims = calculate(image)
        assertTrue(dims.pixelHeight <= paneHeightPx.toInt(), "height still overflows: ${dims.pixelHeight}")
        assertTrue(dims.pixelWidth <= paneWidthPx.toInt(), "width overflows: ${dims.pixelWidth}")
        assertTrue(dims.cellHeight <= rows, "cell span exceeds pane rows: ${dims.cellHeight}")
        assertAspectPreserved(image, dims.pixelWidth, dims.pixelHeight)
    }

    @Test
    fun autoAutoScalesDownWhenBothDimensionsOverflow() {
        val image = autoImage(2000, 800)
        val dims = calculate(image)
        assertTrue(dims.pixelWidth <= paneWidthPx.toInt())
        assertTrue(dims.pixelHeight <= paneHeightPx.toInt())
        assertAspectPreserved(image, dims.pixelWidth, dims.pixelHeight)
    }

    @Test
    fun autoAutoOnRetinaTargetsLogicalSize() {
        // On a 2x display, cell metrics arrive in device px. Without pixelScale
        // a 400x200 image spans only 400 device px = 200 logical px (half size).
        // With pixelScale=2 it must span 800 device px, i.e. its logical size.
        val image = autoImage(400, 200)
        val dims = ImageDimensionCalculator.calculate(
            image = image,
            terminalWidthCells = cols,
            terminalHeightCells = rows,
            cellWidthPx = cellW * 2,   // device px on 2x
            cellHeightPx = cellH * 2,
            pixelScale = 2f
        )
        assertEquals(800, dims.pixelWidth)
        assertEquals(400, dims.pixelHeight)
        // Cell footprint is device-px / device-cell — same count as on a 1x display.
        assertEquals(40, dims.cellWidth)
        assertEquals(10, dims.cellHeight)
    }

    @Test
    fun explicitPercentSpecsAreFittedWithinBounds() {
        // Sanity check on the pre-existing "both specified" branch.
        val image = TerminalImage(
            data = ByteArray(1),
            intrinsicWidth = 960,
            intrinsicHeight = 560,
            widthSpec = DimensionSpec.Percent(90),
            heightSpec = DimensionSpec.Percent(90),
            preserveAspectRatio = true
        )
        val dims = calculate(image)
        assertTrue(dims.pixelWidth <= (paneWidthPx * 0.9f).toInt() + 1)
        assertTrue(dims.pixelHeight <= (paneHeightPx * 0.9f).toInt() + 1)
        assertAspectPreserved(image, dims.pixelWidth, dims.pixelHeight)
    }
}
