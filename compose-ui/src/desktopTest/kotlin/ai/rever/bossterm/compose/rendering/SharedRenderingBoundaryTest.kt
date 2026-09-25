package ai.rever.bossterm.compose.rendering

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import java.net.URLClassLoader
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedRenderingBoundaryTest {
    private fun restrictedLoader(): URLClassLoader {
        val parent = javaClass.classLoader
        val source = ImageRenderer::class.java.protectionDomain.codeSource.location
        return object : URLClassLoader(arrayOf(source), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                synchronized(getClassLoadingLock(name)) {
                    if (name.startsWith("org.jetbrains.skia.") || name.startsWith("org.jetbrains.skiko.")) {
                        throw ClassNotFoundException(name)
                    }
                    if (listOf("ai.rever.bossterm.compose.util.FontUtilsKt",
                            "ai.rever.bossterm.compose.mcp.ImageTranscoderKt").any {
                            name == it || name.startsWith(it + "$")
                        }) {
                        val result = findLoadedClass(name) ?: findClass(name)
                        if (resolve) resolveClass(result)
                        result
                    } else super.loadClass(name, resolve)
                }
        }
    }

    @Test
    fun `WebP transcodes through shared Compose with alpha intact and no direct Skia access`() {
        // Lossless 2x3 WebP, every pixel is RGBA(12, 34, 56, 128).
        val bytes = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAYAAEAdQkTIUp4CBiOh/AAA=")
        restrictedLoader().use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.Image") }
            val transcoder = loader.loadClass("ai.rever.bossterm.compose.mcp.ImageTranscoderKt")
            val png = transcoder.getMethod("transcodeImageToPng", ByteArray::class.java).invoke(null, bytes) as ByteArray
            val decoded = assertNotNull(png.inputStream().use { ImageIO.read(it) })
            assertEquals(2, decoded.width)
            assertEquals(3, decoded.height)
            assertEquals(0x800c2238.toInt(), decoded.getRGB(0, 0))
        }
    }

    @Test
    fun `system font inventory and selected font resolve with direct Skia access blocked`() {
        restrictedLoader().use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.FontMgr") }
            val fonts = loader.loadClass("ai.rever.bossterm.compose.util.FontUtilsKt")
            @Suppress("UNCHECKED_CAST")
            val categories = fonts.getMethod("getCategorizedFonts").invoke(null) as Map<String, List<String>>
            val names = categories.getValue("Fixed Pitch") + categories.getValue("Variable Pitch")
            assertTrue(names.isNotEmpty())
            val logicalNames = listOf("Dialog", "DialogInput", "Monospaced", "Serif", "SansSerif")
            assertTrue(names.none { font -> logicalNames.any { it.equals(font, true) } })
            val physicalName = listOf("Menlo", "DejaVu Sans Mono", "Liberation Mono", "Consolas")
                .firstOrNull { it in names } ?: names.first()
            val family = fonts.getMethod("loadTerminalFont", String::class.java).invoke(null, physicalName) as FontFamily
            val result = createFontFamilyResolver().resolve(family).value
            // Inspect the host-side result; the production utility remains in the restricted loader.
            val typeface = result.javaClass.getMethod("getTypeface").invoke(result) as org.jetbrains.skia.Typeface
            assertEquals(physicalName, typeface.familyName)
        }
    }
}
