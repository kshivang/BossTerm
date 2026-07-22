package ai.rever.bossterm.terminal.emulator.graphics

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal data class DecodedRaster(
    val pngData: ByteArray,
    val width: Int,
    val height: Int
)

internal object RasterCodec {
    const val MAX_ENCODED_BYTES: Int = 50 * 1024 * 1024
    const val MAX_DIMENSION: Int = 16_384
    const val MAX_PIXELS: Long = 64L * 1024 * 1024

    fun readPng(data: ByteArray): DecodedRaster {
        require(data.size <= MAX_ENCODED_BYTES) { "image exceeds the 50 MiB limit" }
        require(
            data.size >= 8 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4e.toByte() && data[3] == 0x47.toByte() &&
                data[4] == 0x0d.toByte() && data[5] == 0x0a.toByte() &&
                data[6] == 0x1a.toByte() && data[7] == 0x0a.toByte()
        ) { "payload is not a PNG image" }
        val image = ImageIO.read(ByteArrayInputStream(data))
            ?: throw IllegalArgumentException("payload is not a decodable PNG image")
        validateDimensions(image.width, image.height)
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
}
