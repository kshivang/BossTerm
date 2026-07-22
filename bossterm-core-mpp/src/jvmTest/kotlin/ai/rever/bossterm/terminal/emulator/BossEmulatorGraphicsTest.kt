package ai.rever.bossterm.terminal.emulator

import ai.rever.bossterm.core.Color
import ai.rever.bossterm.core.util.Ascii
import ai.rever.bossterm.terminal.Terminal
import ai.rever.bossterm.terminal.TerminalColor
import ai.rever.bossterm.terminal.TerminalDataStream
import ai.rever.bossterm.terminal.TextStyle
import ai.rever.bossterm.terminal.model.image.TerminalImage
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class BossEmulatorGraphicsTest {

    @Test
    fun routesKittyApcWithoutWritingProtocolBytesAsText() {
        val recording = RecordingTerminal()
        emulate("${Ascii.ESC}_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA${Ascii.ESC}\\", recording)

        assertEquals("${Ascii.ESC}_Gi=31;OK${Ascii.ESC}\\", recording.responses.single())
        assertEquals(emptyList(), recording.text)
    }

    @Test
    fun routesEightBitSixelDcsToInlineImageRenderer() {
        val recording = RecordingTerminal()
        emulate("${0x90.toChar()}q\"1;1;1;6#1;2;0;100;0~${0x9c.toChar()}", recording)

        assertEquals(1, recording.images.single().intrinsicWidth)
        assertEquals(6, recording.images.single().intrinsicHeight)
        assertEquals(emptyList(), recording.text)
    }

    @Test
    fun oversizedGraphicsControlStringAbortsWithoutKillingTheEmulator() {
        val recording = RecordingTerminal()
        val input = "${Ascii.ESC}_G123${Ascii.CAN}X"

        emulate(input, recording, maxGraphicsControlChars = 4)

        assertEquals(listOf("X"), recording.text)
        assertEquals(emptyList(), recording.images)
    }

    @Test
    fun kittyPlaceholderCoordinatesAreNotSplitAtTextReadLimit() {
        val recording = RecordingTerminal(distanceToLineEnd = 3)
        recording.currentStyle = TextStyle(TerminalColor.index(42), null)
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0x12, 0x34, 0x56, 0xff.toByte()))
        val base = String(Character.toChars(0x10EEEE))
        val input = buildString {
            append(Ascii.ESC).append("_Ga=T,U=1,f=32,s=1,v=1,i=42,C=1;").append(encoded)
            append(Ascii.ESC).append('\\')
            append(base).append("\u0305\u0305")
            append(base).append("\u0305\u030d")
        }

        emulate(input, recording)

        assertEquals(listOf(0 to 0, 1 to 0), recording.placeholderCells)
        assertEquals(emptyList(), recording.text)
    }

    private fun emulate(
        input: String,
        recording: RecordingTerminal,
        maxGraphicsControlChars: Int? = null
    ) {
        val stream = StringDataStream(input)
        val emulator = if (maxGraphicsControlChars == null) {
            BossEmulator(stream, recording.terminal)
        } else {
            BossEmulator(stream, recording.terminal, maxGraphicsControlChars)
        }
        while (emulator.hasNext()) emulator.next()
    }

    private class StringDataStream(input: String) : TerminalDataStream {
        private val remaining = ArrayDeque(input.toList())

        override val char: Char
            get() = remaining.removeFirstOrNull() ?: throw TerminalDataStream.EOF()

        override fun pushChar(c: Char) {
            remaining.addFirst(c)
        }

        override fun readNonControlCharacters(maxChars: Int): String {
            val result = StringBuilder()
            while (result.length < maxChars && remaining.firstOrNull()?.code?.let { it >= 0x20 } == true) {
                result.append(remaining.removeFirst())
            }
            return result.toString()
        }

        override fun pushBackBuffer(bytes: CharArray?, length: Int) {
            if (bytes == null) return
            for (index in length - 1 downTo 0) remaining.addFirst(bytes[index])
        }

        override val isEmpty: Boolean get() = remaining.isEmpty()
    }

    private class RecordingTerminal(private val distanceToLineEnd: Int = 80) {
        val responses = mutableListOf<String>()
        val images = mutableListOf<TerminalImage>()
        val text = mutableListOf<String>()
        val placeholderCells = mutableListOf<Pair<Int, Int>>()
        var currentStyle: TextStyle = TextStyle.EMPTY

        val terminal: Terminal = Proxy.newProxyInstance(
            Terminal::class.java.classLoader,
            arrayOf(Terminal::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "deviceStatusReport" -> responses += arguments?.get(0) as String
                "processInlineImage" -> {
                    images += arguments?.get(0) as TerminalImage
                    null
                }
                "processInlineImagePlaceholder" -> {
                    placeholderCells += (arguments?.get(1) as Int) to (arguments[2] as Int)
                    null
                }
                "currentTextStyle" -> currentStyle
                "writeCharacters" -> text += arguments?.get(0) as String
                "getWindowBackground" -> Color(0, 0, 0)
                "distanceToLineEnd" -> distanceToLineEnd
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
}
