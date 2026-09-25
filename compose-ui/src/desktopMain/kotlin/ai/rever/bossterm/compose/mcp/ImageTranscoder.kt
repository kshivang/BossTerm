package ai.rever.bossterm.compose.mcp

import androidx.compose.ui.res.loadImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Decode through Compose's rendering owner, then encode with the desktop PNG writer. */
internal fun transcodeImageToPng(bytes: ByteArray): ByteArray {
    @Suppress("DEPRECATION")
    val bitmap = bytes.inputStream().use { loadImageBitmap(it) }
    val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
    bitmap.readPixels(pixels)
    val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, bitmap.width, bitmap.height, pixels, 0, bitmap.width)
    return ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output)) { "PNG encoder unavailable" }
        output.toByteArray()
    }
}
