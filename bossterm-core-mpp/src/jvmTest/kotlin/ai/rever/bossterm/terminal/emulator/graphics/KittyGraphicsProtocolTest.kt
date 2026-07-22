package ai.rever.bossterm.terminal.emulator.graphics

import ai.rever.bossterm.core.Color
import ai.rever.bossterm.core.util.Ascii
import ai.rever.bossterm.terminal.Terminal
import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.terminal.model.image.TerminalImagePlacement
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KittyGraphicsProtocolTest {

    @Test
    fun answersTheStandardDirectRgbCapabilityQuery() {
        val recording = RecordingTerminal()

        assertTrue(
            KittyGraphicsProtocol().process(
                "Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA",
                recording.terminal
            )
        )

        assertEquals("${Ascii.ESC}_Gi=31;OK${Ascii.ESC}\\", recording.responses.single())
        assertTrue(recording.images.isEmpty())
    }

    @Test
    fun assemblesChunkedPngAndPreservesCursorWhenRequested() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val png = RasterCodec.encodeArgb(intArrayOf(0xff123456.toInt()), 1, 1).pngData
        val encoded = Base64.getEncoder().encodeToString(png)
        val split = encoded.length / 2

        protocol.process("Ga=T,f=100,i=7,c=2,r=3,C=1,m=1;${encoded.substring(0, split)}", recording.terminal)
        assertTrue(recording.images.isEmpty())
        protocol.process("Gm=0;${encoded.substring(split)}", recording.terminal)

        val placement = recording.images.single()
        assertEquals(7, placement.imageId)
        assertEquals(DimensionSpec.Cells(2), placement.image.widthSpec)
        assertEquals(DimensionSpec.Cells(3), placement.image.heightSpec)
        assertFalse(placement.moveCursor)
        assertEquals(0xff123456.toInt(), ImageIOHelper.argbAt(placement.image, 0, 0))
    }

    @Test
    fun transmitThenPlaceReusesTheStoredImage() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))

        protocol.process("Ga=t,f=32,s=1,v=1,i=9;$encoded", recording.terminal)
        assertTrue(recording.images.isEmpty())
        protocol.process("Ga=p,i=9,C=1", recording.terminal)

        assertEquals(9, recording.images.single().imageId)
        assertFalse(recording.images.single().moveCursor)
    }

    @Test
    fun defaultPlacementMovesCursorByThePlacementRectangle() {
        val recording = RecordingTerminal()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))

        KittyGraphicsProtocol().process("Ga=T,f=32,s=1,v=1,i=10,c=2,r=3;$encoded", recording.terminal)

        assertEquals(listOf(2), recording.cursorForward)
        assertEquals(listOf(3), recording.cursorDown)
        assertFalse(recording.images.single().moveCursor)
    }

    @Test
    fun softDeleteKeepsImageDataButHardDeleteRemovesIt() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))

        protocol.process("Ga=T,f=32,s=1,v=1,i=11,C=1;$encoded", recording.terminal)
        protocol.process("Ga=d,d=i,i=11", recording.terminal)
        protocol.process("Ga=p,i=11,C=1", recording.terminal)
        assertEquals(2, recording.images.size)

        protocol.process("Ga=d,d=I,i=11", recording.terminal)
        protocol.process("Ga=p,i=11,C=1", recording.terminal)
        assertTrue(recording.responses.last().contains("ENOENT"))
    }

    @Test
    fun readsAndCleansUpKittyTemporaryFileTransport() {
        val recording = RecordingTerminal()
        val png = RasterCodec.encodeArgb(intArrayOf(0xffabcdef.toInt()), 1, 1).pngData
        val file = Files.createTempFile("tty-graphics-protocol-", ".png")
        Files.write(file, png)
        val encodedPath = Base64.getEncoder().encodeToString(file.toString().toByteArray())

        KittyGraphicsProtocol().process("Gi=12,a=q,t=t,f=100;$encodedPath", recording.terminal)

        assertEquals("${Ascii.ESC}_Gi=12;OK${Ascii.ESC}\\", recording.responses.single())
        assertFalse(Files.exists(file))
    }

    private data class Placement(val imageId: Long, val image: TerminalImage, val moveCursor: Boolean)

    private class RecordingTerminal {
        val responses = mutableListOf<String>()
        val images = mutableListOf<Placement>()
        val cursorForward = mutableListOf<Int>()
        val cursorDown = mutableListOf<Int>()

        val terminal: Terminal = Proxy.newProxyInstance(
            Terminal::class.java.classLoader,
            arrayOf(Terminal::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "deviceStatusReport" -> {
                    responses += arguments?.get(0) as String
                    null
                }
                "processInlineImage" -> {
                    val image = arguments?.get(0) as TerminalImage
                    val moveCursor = arguments[1] as Boolean
                    images += Placement(image.name!!.removePrefix("kitty-").removeSuffix(".png").toLong(), image, moveCursor)
                    val width = (image.widthSpec as? DimensionSpec.Cells)?.count ?: 1
                    val height = (image.heightSpec as? DimensionSpec.Cells)?.count ?: 1
                    TerminalImagePlacement(image, 0, 0, width, height, width, height)
                }
                "cursorForward" -> cursorForward += arguments?.get(0) as Int
                "cursorDown" -> cursorDown += arguments?.get(0) as Int
                "getWindowBackground" -> Color(0, 0, 0)
                "distanceToLineEnd" -> 80
                "ambiguousCharsAreDoubleWidth" -> false
                "toString" -> "RecordingTerminal"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> defaultValue(method.returnType)
            }
        } as Terminal

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private object ImageIOHelper {
        fun argbAt(image: TerminalImage, x: Int, y: Int): Int {
            val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(image.data))
            assertNotNull(decoded)
            return decoded.getRGB(x, y)
        }
    }
}
