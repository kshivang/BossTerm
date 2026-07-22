package ai.rever.bossterm.terminal.emulator.graphics

import ai.rever.bossterm.core.Color
import ai.rever.bossterm.core.util.Ascii
import ai.rever.bossterm.terminal.Terminal
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.TerminalImage
import ai.rever.bossterm.terminal.model.image.TerminalImagePlacement
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
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
    fun capabilityQueryWithoutIdentifierStillResponds() {
        val recording = RecordingTerminal()

        KittyGraphicsProtocol().process("Ga=q,s=1,v=1,t=d,f=24;AAAA", recording.terminal)

        assertEquals("${Ascii.ESC}_G;OK${Ascii.ESC}\\", recording.responses.single())
    }

    @Test
    fun transmitWithoutIdentifierStaysSilent() {
        val recording = RecordingTerminal()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))

        KittyGraphicsProtocol().process("Ga=T,f=32,s=1,v=1,C=1;$encoded", recording.terminal)

        assertEquals(1, recording.images.size)
        assertTrue(recording.responses.isEmpty())
    }

    @Test
    fun graphicsControlStringHasHeadroomBeyondMaximumPayload() {
        assertTrue(RasterCodec.MAX_CONTROL_STRING_CHARS > RasterCodec.MAX_BASE64_CHARS)
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
    fun unicodePlaceholderUsesVirtualPlacementWithoutPrintingItsGlyphs() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val externalId = 0x0179c4L
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))

        protocol.process("Ga=T,U=1,f=32,s=1,v=1,i=$externalId,c=4,r=5,C=1;$encoded", recording.terminal)
        assertTrue(recording.images.isEmpty())

        recording.currentStyle = TextStyle(TerminalColor.rgb(0x01, 0x79, 0xc4), null)
        val placeholder = String(Character.toChars(KittyUnicodePlaceholder.CODE_POINT)) + "\u030e\u0310"
        assertTrue(protocol.processText("left$placeholder right", recording.terminal))

        val cell = recording.placeholderCells.single()
        assertEquals(externalId, cell.imageId)
        assertEquals(3, cell.cellX)
        assertEquals(2, cell.cellY)
        assertEquals(DimensionSpec.Cells(4), cell.image.widthSpec)
        assertEquals(DimensionSpec.Cells(5), cell.image.heightSpec)
        assertTrue(cell.image.preserveAspectRatio)
        assertEquals(listOf("left", " right"), recording.writtenText)
    }

    @Test
    fun unicodePlaceholderInheritsColumnsAndDecodesHighImageIdByte() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val externalId = 42L + (2L shl 24)
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))
        protocol.process("Ga=T,U=1,f=32,s=1,v=1,i=$externalId,C=1;$encoded", recording.terminal)
        recording.currentStyle = TextStyle(TerminalColor.index(42), null)

        val base = String(Character.toChars(KittyUnicodePlaceholder.CODE_POINT))
        val first = base + "\u0305\u0305\u030e"
        assertTrue(protocol.processText(first + base, recording.terminal))

        assertEquals(listOf(0, 1), recording.placeholderCells.map { it.cellX })
        assertEquals(listOf(0, 0), recording.placeholderCells.map { it.cellY })
        assertTrue(recording.writtenText.isEmpty())
    }

    @Test
    fun unicodePlaceholderInheritanceContinuesAcrossAdjacentTextBatches() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))
        protocol.process("Ga=T,U=1,f=32,s=1,v=1,i=42,C=1;$encoded", recording.terminal)
        recording.currentStyle = TextStyle(TerminalColor.index(42), null)
        val base = String(Character.toChars(KittyUnicodePlaceholder.CODE_POINT))

        assertTrue(protocol.processText(base + "\u0305", recording.terminal))
        assertTrue(protocol.processText(base, recording.terminal))
        assertTrue(protocol.processText(base, recording.terminal))

        assertEquals(listOf(0, 1, 2), recording.placeholderCells.map { it.cellX })
        assertEquals(listOf(0, 0, 0), recording.placeholderCells.map { it.cellY })
    }

    @Test
    fun screenWideDeleteDoesNotDeleteVirtualPlacements() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol()
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))
        protocol.process("Ga=T,U=1,f=32,s=1,v=1,i=42,C=1;$encoded", recording.terminal)
        protocol.process("Ga=d,d=A,q=2", recording.terminal)

        recording.currentStyle = TextStyle(TerminalColor.index(42), null)
        val placeholder = String(Character.toChars(KittyUnicodePlaceholder.CODE_POINT)) + "\u0305\u0305"
        assertTrue(protocol.processText(placeholder, recording.terminal))

        assertEquals(1, recording.placeholderCells.size)
        assertEquals(recording.placeholderCells.mapTo(mutableSetOf()) { it.image.id }, recording.retainedOnClear.single())
    }

    @Test
    fun missingPlaceholderPrototypeBecomesBlankInsteadOfLeakingPrivateUseGlyphs() {
        val recording = RecordingTerminal()
        recording.currentStyle = TextStyle(TerminalColor.index(42), null)
        val placeholder = String(Character.toChars(KittyUnicodePlaceholder.CODE_POINT)) + "\u0305\u0305"

        assertTrue(KittyGraphicsProtocol().processText(placeholder, recording.terminal))

        assertEquals(listOf(" "), recording.writtenText)
        assertTrue(recording.placeholderCells.isEmpty())
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

    @Test
    fun fileTransportCanBeDisabledWithoutDisablingDirectImages() {
        val recording = RecordingTerminal()
        val protocol = KittyGraphicsProtocol(allowFileTransfers = false)
        val encodedPath = encodedPath("/tmp/example.png")

        protocol.process("Gi=13,a=q,t=f,f=100;$encodedPath", recording.terminal)
        val direct = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))
        protocol.process("Gi=14,a=q,t=d,f=32,s=1,v=1;$direct", recording.terminal)

        assertTrue(recording.responses[0].contains("EACCES: file-backed Kitty transfers are disabled"))
        assertEquals("${Ascii.ESC}_Gi=14;OK${Ascii.ESC}\\", recording.responses[1])
    }

    @Test
    fun rejectsRelativeAndNulFileTransportPaths() {
        val relative = RecordingTerminal()
        KittyGraphicsProtocol().process(
            "Gi=15,a=q,t=f,f=100;${encodedPath("image.png")}",
            relative.terminal
        )

        val nul = RecordingTerminal()
        KittyGraphicsProtocol().process(
            "Gi=16,a=q,t=f,f=100;${encodedPath("/tmp/image\u0000.png")}",
            nul.terminal
        )

        assertTrue(relative.responses.single().contains("EINVAL: image file path must be absolute"))
        assertTrue(nul.responses.single().contains("EINVAL: invalid image file path"))
    }

    @Test
    fun rejectsSensitiveDevicePathsAfterCanonicalization() {
        val recording = RecordingTerminal()

        KittyGraphicsProtocol().process(
            "Gi=17,a=q,t=f,f=100;${encodedPath("/dev/null")}",
            recording.terminal
        )

        assertTrue(recording.responses.single().contains("EACCES: image path is not allowed"))
    }

    @Test
    fun sensitiveFileTransportRootsStayDenylisted() {
        val protocol = KittyGraphicsProtocol()

        assertTrue(protocol.isSensitivePath(Path.of("/proc/version")))
        assertTrue(protocol.isSensitivePath(Path.of("/sys/kernel")))
        assertTrue(protocol.isSensitivePath(Path.of("/dev/null")))
        assertFalse(protocol.isSensitivePath(Path.of("/dev/shm/image.png")))
    }

    @Test
    fun temporaryTransportDoesNotDeleteOutsideApprovedTemporaryRoots() {
        val recording = RecordingTerminal()
        val png = RasterCodec.encodeArgb(intArrayOf(0xffabcdef.toInt()), 1, 1).pngData
        val file = Files.createTempFile(
            Path.of("").toAbsolutePath(),
            "tty-graphics-protocol-",
            ".png"
        )
        try {
            Files.write(file, png)

            KittyGraphicsProtocol().process(
                "Gi=18,a=q,t=t,f=100;${encodedPath(file.toString())}",
                recording.terminal
            )

            assertEquals("${Ascii.ESC}_Gi=18;OK${Ascii.ESC}\\", recording.responses.single())
            assertTrue(Files.exists(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun encodedPath(path: String): String =
        Base64.getEncoder().encodeToString(path.toByteArray())

    private data class Placement(val imageId: Long, val image: TerminalImage, val moveCursor: Boolean)
    private data class PlaceholderCell(val imageId: Long, val image: TerminalImage, val cellX: Int, val cellY: Int)

    private class RecordingTerminal {
        val responses = mutableListOf<String>()
        val images = mutableListOf<Placement>()
        val cursorForward = mutableListOf<Int>()
        val cursorDown = mutableListOf<Int>()
        val placeholderCells = mutableListOf<PlaceholderCell>()
        val writtenText = mutableListOf<String>()
        val retainedOnClear = mutableListOf<Set<Long>>()
        var currentStyle: TextStyle = TextStyle.EMPTY

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
                "processInlineImagePlaceholder" -> {
                    val image = arguments?.get(0) as TerminalImage
                    placeholderCells += PlaceholderCell(
                        image.name!!.removePrefix("kitty-").removeSuffix(".png").toLong(),
                        image,
                        arguments[1] as Int,
                        arguments[2] as Int
                    )
                    null
                }
                "currentTextStyle" -> currentStyle
                "writeCharacters" -> {
                    writtenText += arguments?.get(0) as String
                    null
                }
                "clearImagesExcept" -> {
                    @Suppress("UNCHECKED_CAST")
                    retainedOnClear += (arguments?.get(0) as Set<Long>).toSet()
                    null
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
