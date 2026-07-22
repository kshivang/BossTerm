package ai.rever.bossterm.terminal.emulator.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal class DecodedRaster(
    val pngData: ByteArray,
    val width: Int,
    val height: Int
)

/**
 * Bounds both encoded input and decoded working memory.
 *
 * ARGB conversion can temporarily retain the source [IntArray] and a
 * [BufferedImage] at the same time. The 16M-pixel ceiling therefore permits
 * roughly 128 MiB of pixel buffers at peak, plus the encoded PNG output. The
 * 50 MiB encoded-image quotas elsewhere are cache limits, not total decoder
 * working-memory limits.
 */
internal object RasterCodec {
    const val MAX_ENCODED_BYTES: Int = 50 * 1024 * 1024
    const val MAX_BASE64_CHARS: Int = 70 * 1024 * 1024
    // Leave room for Kitty control fields and future extensions around a
    // maximum-size encoded payload.
    const val MAX_CONTROL_STRING_CHARS: Int = MAX_BASE64_CHARS + 64 * 1024
    const val MAX_DIMENSION: Int = 16_384
    const val MAX_PIXELS: Long = 16L * 1024 * 1024

    fun readPng(data: ByteArray): DecodedRaster {
        require(data.size <= MAX_ENCODED_BYTES) { "image exceeds the 50 MiB limit" }
        require(
            data.size >= 8 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4e.toByte() && data[3] == 0x47.toByte() &&
                data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() &&
                data[6] == 0x1a.toByte() && data[7] == 0x0a.toByte()
        ) { "payload is not a PNG image" }
        require(data.size >= PNG_DIMENSIONS_END_OFFSET) { "PNG payload is truncated before IHDR dimensions" }
        require(readUnsignedInt(data, 8) == PNG_IHDR_DATA_BYTES.toLong()) { "PNG does not begin with a valid IHDR" }
        require(
            data[12] == 'I'.code.toByte() && data[13] == 'H'.code.toByte() &&
                data[14] == 'D'.code.toByte() && data[15] == 'R'.code.toByte()
        ) { "PNG does not begin with an IHDR chunk" }

        val declaredWidth = readUnsignedInt(data, 16)
        val declaredHeight = readUnsignedInt(data, 20)
        require(declaredWidth <= Int.MAX_VALUE && declaredHeight <= Int.MAX_VALUE) {
            "PNG dimensions are outside the supported range"
        }
        // Validate IHDR before ImageIO allocates the decoded raster. This is the
        // decompression-bomb boundary for f=100 and zlib-wrapped PNG payloads.
        validateDimensions(declaredWidth.toInt(), declaredHeight.toInt())

        val image = try {
            ImageIO.read(ByteArrayInputStream(data))
                ?: throw IllegalArgumentException("payload is not a decodable PNG image")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("payload is not a decodable PNG image")
        }
        validateDimensions(image.width, image.height)
        require(image.width == declaredWidth.toInt() && image.height == declaredHeight.toInt()) {
            "decoded PNG dimensions do not match IHDR"
        }
        return DecodedRaster(data, image.width, image.height)
    }

    fun encodeArgb(pixels: IntArray, width: Int, height: Int): DecodedRaster {
        validateDimensions(width, height)
        require(pixels.size == width * height) { "pixel buffer size does not match image dimensions" }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output)) { "PNG encoder is unavailable" }
        return DecodedRaster(output.toByteArray(), width, height)
    }

    fun encodeRaw(data: ByteArray, width: Int, height: Int, bytesPerPixel: Int): DecodedRaster {
        validateDimensions(width, height)
        require(bytesPerPixel == 3 || bytesPerPixel == 4) { "unsupported raw pixel format" }
        val expected = width.toLong() * height * bytesPerPixel
        require(expected <= MAX_ENCODED_BYTES && data.size.toLong() == expected) {
            "raw payload size does not match image dimensions"
        }

        val pixels = IntArray(width * height)
        var source = 0
        for (index in pixels.indices) {
            val red = data[source++].toInt() and 0xff
            val green = data[source++].toInt() and 0xff
            val blue = data[source++].toInt() and 0xff
            val alpha = if (bytesPerPixel == 4) data[source++].toInt() and 0xff else 0xff
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return encodeArgb(pixels, width, height)
    }

    fun validateDimensions(width: Int, height: Int) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "image dimensions are outside the supported range"
        }
        require(width.toLong() * height <= MAX_PIXELS) { "image exceeds the pixel limit" }
    }

    private fun readUnsignedInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private const val PNG_IHDR_DATA_BYTES = 13
    private const val PNG_DIMENSIONS_END_OFFSET = 24
}
