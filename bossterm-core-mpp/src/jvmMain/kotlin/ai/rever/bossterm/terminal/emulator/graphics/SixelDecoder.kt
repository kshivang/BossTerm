package ai.rever.bossterm.terminal.emulator.graphics

import kotlin.math.roundToInt

/** Decodes the DEC sixel payload that follows the DCS `q` final byte. */
internal object SixelDecoder {
    private const val MAX_REPEAT = RasterCodec.MAX_DIMENSION

    fun decode(data: String, aspectParameter: Int = 0, backgroundArgb: Int = 0): DecodedRaster {
        val palette = defaultPalette()
        var selectedColor = 0
        var cursorX = 0
        var cursorY = 0
        var rasterWidth = 0
        var rasterHeight = 0
        var pixelAspect = aspectRatioFor(aspectParameter)
        val canvas = PixelCanvas(backgroundArgb)
        var index = 0

        while (index < data.length) {
            when (val ch = data[index]) {
                '!' -> {
                    val (count, afterCount) = parseNumber(data, index + 1)
                    if (afterCount < data.length && data[afterCount] in '?'..'~') {
                        val repeat = count.coerceIn(1, MAX_REPEAT)
                        canvas.paintSixel(cursorX, cursorY, data[afterCount].code - '?'.code, repeat, palette[selectedColor])
                        cursorX += repeat
                        index = afterCount + 1
                    } else {
                        index = afterCount.coerceAtLeast(index + 1)
                    }
                }

                '#' -> {
                    val parsed = parseParameters(data, index + 1)
                    if (parsed.values.isNotEmpty()) {
                        selectedColor = parsed.values[0].coerceIn(0, palette.lastIndex)
                        if (parsed.values.size >= 5) {
                            val color = when (parsed.values[1]) {
                                1 -> hlsToArgb(parsed.values[2], parsed.values[3], parsed.values[4])
                                2 -> rgbPercentToArgb(parsed.values[2], parsed.values[3], parsed.values[4])
                                else -> palette[selectedColor]
                            }
                            palette[selectedColor] = color
                        }
                    }
                    index = parsed.nextIndex
                }

                '"' -> {
                    val parsed = parseParameters(data, index + 1)
                    if (parsed.values.size >= 2 && parsed.values[0] > 0 && parsed.values[1] > 0) {
                        pixelAspect = parsed.values[0].toDouble() / parsed.values[1]
                    }
                    if (parsed.values.size >= 4) {
                        rasterWidth = parsed.values[2].coerceIn(0, RasterCodec.MAX_DIMENSION)
                        rasterHeight = parsed.values[3].coerceIn(0, RasterCodec.MAX_DIMENSION)
                        if (rasterWidth > 0 && rasterHeight > 0) {
                            canvas.ensureSize(rasterWidth, rasterHeight)
                        }
                    }
                    index = parsed.nextIndex
                }

                '$' -> {
                    cursorX = 0
                    index++
                }

                '-' -> {
                    cursorX = 0
                    cursorY += 6
                    require(cursorY <= RasterCodec.MAX_DIMENSION) { "sixel image exceeds the height limit" }
                    index++
                }

                in '?'..'~' -> {
                    canvas.paintSixel(cursorX, cursorY, ch.code - '?'.code, 1, palette[selectedColor])
                    cursorX++
                    index++
                }

                else -> index++
            }
        }

        // Explicit raster dimensions clip the final sixel band. Without this,
        // a one-pixel-high image still becomes six pixels high because sixel
        // data is encoded in six-row bands.
        val logicalWidth = if (rasterWidth > 0) rasterWidth else maxOf(1, canvas.usedWidth)
        val logicalHeight = if (rasterHeight > 0) rasterHeight else maxOf(1, canvas.usedHeight)
        RasterCodec.validateDimensions(logicalWidth, logicalHeight)
        val source = canvas.copyPixels(logicalWidth, logicalHeight)

        val outputHeight = (logicalHeight * pixelAspect)
            .roundToInt()
            .coerceAtLeast(1)
        RasterCodec.validateDimensions(logicalWidth, outputHeight)
        val output = if (outputHeight == logicalHeight) {
            source
        } else {
            scaleRowsNearest(source, logicalWidth, logicalHeight, outputHeight)
        }
        return RasterCodec.encodeArgb(output, logicalWidth, outputHeight)
    }

    private data class ParsedParameters(val values: List<Int>, val nextIndex: Int)

    private fun parseParameters(data: String, start: Int): ParsedParameters {
        val values = mutableListOf<Int>()
        var index = start
        while (index < data.length && (data[index].isDigit() || data[index] == ';')) {
            val (value, afterValue) = parseNumber(data, index)
            values.add(value)
            index = afterValue
            if (index < data.length && data[index] == ';') {
                index++
                if (index == data.length || !data[index].isDigit()) values.add(0)
            } else {
                break
            }
        }
        return ParsedParameters(values, index)
    }

    private fun parseNumber(data: String, start: Int): Pair<Int, Int> {
        var index = start
        var value = 0L
        while (index < data.length && data[index].isDigit()) {
            value = (value * 10 + (data[index].code - '0'.code)).coerceAtMost(Int.MAX_VALUE.toLong())
            index++
        }
        return value.toInt() to index
    }

    private fun aspectRatioFor(parameter: Int): Double = when (parameter) {
        2 -> 5.0
        3, 4 -> 3.0
        5, 6 -> 2.0
        7, 8, 9 -> 1.0
        else -> 2.0
    }

    private fun scaleRowsNearest(source: IntArray, width: Int, sourceHeight: Int, targetHeight: Int): IntArray {
        val result = IntArray(width * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = (targetY.toLong() * sourceHeight / targetHeight).toInt().coerceAtMost(sourceHeight - 1)
            source.copyInto(result, targetY * width, sourceY * width, (sourceY + 1) * width)
        }
        return result
    }

    private fun rgbPercentToArgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or
            (percentToByte(red) shl 16) or
            (percentToByte(green) shl 8) or
            percentToByte(blue)

    /** DEC HLS uses blue, rather than red, as hue zero. */
    private fun hlsToArgb(hue: Int, lightness: Int, saturation: Int): Int {
        val h = (((hue % 360) + 360 + 240) % 360) / 360.0
        val l = lightness.coerceIn(0, 100) / 100.0
        val s = saturation.coerceIn(0, 100) / 100.0
        if (s == 0.0) {
            val gray = (l * 255).roundToInt()
            return (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        val red = hueChannel(p, q, h + 1.0 / 3)
        val green = hueChannel(p, q, h)
        val blue = hueChannel(p, q, h - 1.0 / 3)
        return (0xff shl 24) or
            ((red * 255).roundToInt() shl 16) or
            ((green * 255).roundToInt() shl 8) or
            (blue * 255).roundToInt()
    }

    private fun hueChannel(p: Double, q: Double, original: Double): Double {
        var value = original
        if (value < 0) value++
        if (value > 1) value--
        return when {
            value < 1.0 / 6 -> p + (q - p) * 6 * value
            value < 1.0 / 2 -> q
            value < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - value) * 6
            else -> p
        }
    }

    private fun percentToByte(value: Int): Int =
        (value.coerceIn(0, 100) * 255.0 / 100.0).roundToInt()

    private fun defaultPalette(): IntArray {
        val palette = IntArray(256) { 0xff000000.toInt() }
        val ansi = intArrayOf(
            0x000000, 0xcd0000, 0x00cd00, 0xcdcd00,
            0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00,
            0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff
        )
        for (index in ansi.indices) palette[index] = 0xff000000.toInt() or ansi[index]
        var index = 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (red in levels) for (green in levels) for (blue in levels) {
            palette[index++] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        for (grayIndex in 0 until 24) {
            val gray = 8 + grayIndex * 10
            palette[232 + grayIndex] = (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        return palette
    }

    private class PixelCanvas(private val backgroundArgb: Int) {
        private var width = 0
        private var height = 0
        private var pixels = IntArray(0)
        var usedWidth: Int = 0
            private set
        var usedHeight: Int = 0
            private set

        fun paintSixel(x: Int, y: Int, bits: Int, repeat: Int, color: Int) {
            require(x >= 0 && x.toLong() + repeat <= RasterCodec.MAX_DIMENSION) {
                "sixel image exceeds the width limit"
            }
            ensureSize(x + repeat, y + 6)
            for (offset in 0 until repeat) {
                for (bit in 0 until 6) {
                    if (bits and (1 shl bit) != 0) pixels[(y + bit) * width + x + offset] = color
                }
            }
            usedWidth = maxOf(usedWidth, x + repeat)
            usedHeight = maxOf(usedHeight, y + 6)
        }

        fun ensureSize(requiredWidth: Int, requiredHeight: Int) {
            if (requiredWidth <= width && requiredHeight <= height) return
            RasterCodec.validateDimensions(requiredWidth.coerceAtLeast(1), requiredHeight.coerceAtLeast(1))
            val newWidth = grow(width, requiredWidth)
            val newHeight = grow(height, requiredHeight)
            RasterCodec.validateDimensions(newWidth, newHeight)
            val replacement = IntArray(newWidth * newHeight) { backgroundArgb }
            for (row in 0 until height) {
                pixels.copyInto(replacement, row * newWidth, row * width, row * width + width)
            }
            width = newWidth
            height = newHeight
            pixels = replacement
        }

        fun copyPixels(targetWidth: Int, targetHeight: Int): IntArray {
            ensureSize(targetWidth, targetHeight)
            val result = IntArray(targetWidth * targetHeight) { backgroundArgb }
            for (row in 0 until targetHeight) {
                pixels.copyInto(result, row * targetWidth, row * width, row * width + targetWidth)
            }
            return result
        }

        private fun grow(current: Int, required: Int): Int {
            var candidate = current.coerceAtLeast(16)
            while (candidate < required) {
                candidate = (candidate * 2).coerceAtMost(RasterCodec.MAX_DIMENSION)
                if (candidate == RasterCodec.MAX_DIMENSION) break
            }
            return maxOf(candidate, required)
        }
    }
}
