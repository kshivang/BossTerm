package ai.rever.bossterm.terminal.emulator.graphics

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class RasterCodecTest {

    @Test
    fun rejectsOversizedIhdrBeforeDecodingPixelData() {
        val pngHeader = pngHeader(width = 30_000, height = 30_000)

        val error = assertFailsWith<IllegalArgumentException> {
            RasterCodec.readPng(pngHeader)
        }

        assertContains(error.message.orEmpty(), "dimensions")
    }

    @Test
    fun normalizesTruncatedPngDecodeFailure() {
        val pngHeader = pngHeader(width = 1, height = 1)

        val error = assertFailsWith<IllegalArgumentException> {
            RasterCodec.readPng(pngHeader)
        }

        assertContains(error.message.orEmpty(), "decodable PNG")
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = ByteArray(24).apply {
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47,
            0x0d, 0x0a, 0x1a, 0x0a
        ).copyInto(this)
        writeUnsignedInt(offset = 8, value = 13)
        this[12] = 'I'.code.toByte()
        this[13] = 'H'.code.toByte()
        this[14] = 'D'.code.toByte()
        this[15] = 'R'.code.toByte()
        writeUnsignedInt(offset = 16, value = width)
        writeUnsignedInt(offset = 20, value = height)
    }

    private fun ByteArray.writeUnsignedInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
