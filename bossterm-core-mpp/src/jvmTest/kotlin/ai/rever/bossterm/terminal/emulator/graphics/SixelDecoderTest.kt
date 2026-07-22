package ai.rever.bossterm.terminal.emulator.graphics

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class SixelDecoderTest {

    @Test
    fun decodesRasterAttributesAndRgbPalette() {
        val raster = SixelDecoder.decode("\"1;1;2;6#1;2;100;0;0~~")
        val image = ImageIO.read(ByteArrayInputStream(raster.pngData))

        assertEquals(2, raster.width)
        assertEquals(6, raster.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xffff0000.toInt(), image.getRGB(1, 5))
    }

    @Test
    fun repeatAndNewLineExpandTheCanvas() {
        val raster = SixelDecoder.decode("\"1;1!3~-?", backgroundArgb = 0)
        val image = ImageIO.read(ByteArrayInputStream(raster.pngData))

        assertEquals(3, raster.width)
        assertEquals(12, raster.height)
        assertEquals(0xff000000.toInt(), image.getRGB(2, 5))
        assertEquals(0x00000000, image.getRGB(0, 6))
    }

    @Test
    fun legacyAspectParameterScalesRows() {
        val raster = SixelDecoder.decode("~", aspectParameter = 2)

        assertEquals(1, raster.width)
        assertEquals(30, raster.height)
    }

    @Test
    fun explicitRasterClipsTheUnusedRowsInTheLastBand() {
        val raster = SixelDecoder.decode("\"1;1;1;1#224;2;100;0;0#224@", aspectParameter = 9)

        assertEquals(1, raster.width)
        assertEquals(1, raster.height)
    }
}
