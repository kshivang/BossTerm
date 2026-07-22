package ai.rever.bossterm.terminal.emulator.graphics

import ai.rever.bossterm.core.util.Ascii
import ai.rever.bossterm.terminal.Terminal
import ai.rever.bossterm.terminal.model.image.DimensionSpec
import ai.rever.bossterm.terminal.model.image.ImageFormat
import ai.rever.bossterm.terminal.model.image.TerminalImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * Stateful implementation of the commonly used Kitty graphics protocol commands.
 *
 * Stored transfers and placed terminal images have independent 50 MiB cache
 * budgets. Placement copies retain the same [ByteArray] reference, so one image
 * is not duplicated, but disjoint stored-only and placed-only sets can retain up
 * to the combined budget.
 */
internal class KittyGraphicsProtocol {
    private data class Command(
        val controls: Map<Char, String>,
        val payload: String
    )

    private data class PendingTransfer(
        val controls: Map<Char, String>,
        val payload: StringBuilder
    )

    private val imagesById = linkedMapOf<Long, TerminalImage>()
    private val imageIdsByNumber = mutableMapOf<Long, Long>()
    private var pendingTransfer: PendingTransfer? = null
    private var nextGeneratedId = 1L
    private var storedBytes = 0L

    fun process(apcBody: String, terminal: Terminal?): Boolean {
        if (!apcBody.startsWith('G')) return false
        val command = try {
            parseCommand(apcBody.drop(1))
        } catch (error: IllegalArgumentException) {
            respond(terminal, emptyMap(), success = false, message = error.message ?: "EINVAL")
            return true
        }
        var responseControls = command.controls

        return try {
            if (command.controls['a'] == "d") {
                // A delete command aborts any unfinished upload before applying
                // its selector, as required by the protocol.
                pendingTransfer = null
                processComplete(command, terminal)
                return true
            }
            if (command.controls['m'] == "1") {
                val pending = pendingTransfer
                if (pending == null) {
                    require(command.payload.length <= RasterCodec.MAX_BASE64_CHARS) {
                        "EFBIG: encoded payload exceeds the limit"
                    }
                    pendingTransfer = PendingTransfer(command.controls, StringBuilder(command.payload))
                } else {
                    appendChunk(pending.payload, command.payload)
                }
                return true
            }

            val pending = pendingTransfer
            pendingTransfer = null
            val complete = if (pending != null) {
                appendChunk(pending.payload, command.payload)
                Command(pending.controls + command.controls.filterKeys { it == 'q' || it == 'm' }, pending.payload.toString())
            } else {
                command
            }
            responseControls = complete.controls

            processComplete(complete, terminal)
            true
        } catch (error: IllegalArgumentException) {
            pendingTransfer = null
            respond(terminal, responseControls, success = false, message = error.message ?: "EINVAL")
            true
        }
    }

    fun reset() {
        pendingTransfer = null
        imagesById.clear()
        imageIdsByNumber.clear()
        storedBytes = 0
    }

    private fun processComplete(command: Command, terminal: Terminal?) {
        require(!command.controls.containsKey('i') || !command.controls.containsKey('I')) {
            "EINVAL: i and I cannot be specified together"
        }
        val action = command.controls['a']?.firstOrNull() ?: 't'
        when (action) {
            'q' -> {
                decode(command)
                respond(terminal, command.controls, success = true)
            }

            'T', 't' -> {
                val raster = decode(command)
                val externalId = imageIdFor(command.controls)
                val image = TerminalImage(
                    data = raster.pngData,
                    name = "kitty-$externalId.png",
                    format = ImageFormat.PNG,
                    intrinsicWidth = raster.width,
                    intrinsicHeight = raster.height
                )
                storeImage(externalId, image)
                imageNumberFor(command.controls)?.let { number -> imageIdsByNumber[number] = externalId }
                if (action == 'T') {
                    place(image, command.controls, terminal)
                }
                if (responseRequested(command.controls)) {
                    respond(terminal, command.controls + ('i' to externalId.toString()), success = true)
                }
            }

            'p' -> {
                val image = findImage(command.controls)
                place(image, command.controls, terminal)
                respond(terminal, command.controls, success = true)
            }

            'd' -> {
                delete(command.controls, terminal)
                respond(terminal, command.controls, success = true)
            }

            else -> throw IllegalArgumentException("ENOTSUP: unsupported Kitty graphics action")
        }
    }

    private fun decode(command: Command): DecodedRaster {
        val medium = command.controls['t']?.firstOrNull() ?: 'd'
        require(command.payload.length <= RasterCodec.MAX_BASE64_CHARS) {
            "EFBIG: encoded payload exceeds the limit"
        }

        val payload = try {
            Base64.getDecoder().decode(command.payload)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("EINVAL: invalid base64 payload")
        }
        var decoded = when (medium) {
            'd' -> payload
            'f', 't' -> readFilePayload(payload, command.controls, deleteAfterRead = medium == 't')
            else -> throw IllegalArgumentException("ENOTSUP: unsupported Kitty transmission medium")
        }
        if (command.controls['o'] == "z") decoded = inflateBounded(decoded)
        require(decoded.size <= RasterCodec.MAX_ENCODED_BYTES) { "EFBIG: decoded payload exceeds the limit" }

        return when (command.controls['f']?.toIntOrNull() ?: 32) {
            100 -> RasterCodec.readPng(decoded)
            24 -> RasterCodec.encodeRaw(decoded, requiredDimension(command, 's'), requiredDimension(command, 'v'), 3)
            32 -> RasterCodec.encodeRaw(decoded, requiredDimension(command, 's'), requiredDimension(command, 'v'), 4)
            else -> throw IllegalArgumentException("ENOTSUP: unsupported Kitty pixel format")
        }
    }

    private fun place(image: TerminalImage, controls: Map<Char, String>, terminal: Terminal?) {
        val columns = placementDimension(controls['c'])
        val rows = placementDimension(controls['r'])
        val sizedImage = image.copy(
            widthSpec = columns?.let(DimensionSpec::Cells) ?: DimensionSpec.Auto,
            heightSpec = rows?.let(DimensionSpec::Cells) ?: DimensionSpec.Auto,
            preserveAspectRatio = columns == null || rows == null
        )
        val placement = terminal?.processInlineImage(sizedImage, moveCursor = false)
        if (controls['C'] != "1" && placement != null) {
            // Kitty moves relative to the original cursor in both axes. This is
            // distinct from iTerm2's convention of moving to column zero below
            // the image, which is BossTerminal's normal inline-image behavior.
            terminal.cursorForward(placement.cellWidth)
            terminal.cursorDown(placement.cellHeight)
        }
    }

    private fun delete(controls: Map<Char, String>, terminal: Terminal?) {
        pendingTransfer = null
        val selector = controls['d']?.firstOrNull() ?: 'a'
        when (selector) {
            'a', 'A' -> {
                terminal?.clearAllImages()
                if (selector == 'A') reset()
            }
            'i', 'I' -> {
                val externalId = controls['i']?.toLongOrNull()
                    ?: throw IllegalArgumentException("EINVAL: delete-by-id requires i")
                val image = imagesById[externalId] ?: return
                terminal?.removeInlineImage(image.id)
                if (selector == 'I') removeStoredImage(externalId)
            }
            'n', 'N' -> {
                val number = controls['I']?.toLongOrNull()
                    ?: throw IllegalArgumentException("EINVAL: delete-by-number requires I")
                val externalId = imageIdsByNumber[number] ?: return
                val image = imagesById[externalId] ?: return
                terminal?.removeInlineImage(image.id)
                if (selector == 'N') removeStoredImage(externalId)
            }
            else -> throw IllegalArgumentException("ENOTSUP: unsupported Kitty deletion selector")
        }
    }

    private fun storeImage(externalId: Long, image: TerminalImage) {
        require(image.data.size <= MAX_STORED_BYTES) { "ENOSPC: image exceeds the storage quota" }
        imagesById.remove(externalId)?.let { storedBytes -= it.data.size }
        while (storedBytes + image.data.size > MAX_STORED_BYTES || imagesById.size >= MAX_STORED_IMAGES) {
            val oldest = imagesById.entries.firstOrNull() ?: break
            imagesById.remove(oldest.key)
            storedBytes -= oldest.value.data.size
            imageIdsByNumber.entries.removeIf { it.value == oldest.key }
        }
        imagesById[externalId] = image
        storedBytes += image.data.size
    }

    private fun removeStoredImage(externalId: Long) {
        imagesById.remove(externalId)?.let { storedBytes -= it.data.size }
        imageIdsByNumber.entries.removeIf { it.value == externalId }
    }

    private fun findImage(controls: Map<Char, String>): TerminalImage {
        require(!controls.containsKey('i') || !controls.containsKey('I')) {
            "EINVAL: i and I cannot be specified together"
        }
        controls['i']?.toLongOrNull()?.let { id ->
            imagesById[id]?.let { return it }
        }
        imageNumberFor(controls)?.let { number ->
            imageIdsByNumber[number]?.let(imagesById::get)?.let { return it }
        }
        throw IllegalArgumentException("ENOENT: Kitty image was not found")
    }

    private fun imageIdFor(controls: Map<Char, String>): Long {
        require(!controls.containsKey('i') || !controls.containsKey('I')) {
            "EINVAL: i and I cannot be specified together"
        }
        val requested = controls['i']?.toLongOrNull()
        if (requested != null) {
            require(requested in 1..UINT32_MAX) { "EINVAL: invalid Kitty image id" }
            return requested
        }
        while (imagesById.containsKey(nextGeneratedId)) nextGeneratedId++
        return nextGeneratedId++
    }

    private fun imageNumberFor(controls: Map<Char, String>): Long? {
        val numberText = controls['I'] ?: return null
        val number = numberText.toLongOrNull()
            ?: throw IllegalArgumentException("EINVAL: invalid Kitty image number")
        require(number in 1..UINT32_MAX) { "EINVAL: invalid Kitty image number" }
        return number
    }

    private fun requiredDimension(command: Command, key: Char): Int {
        val value = positiveInt(command.controls[key])
            ?: throw IllegalArgumentException("EINVAL: raw image requires $key")
        require(value <= RasterCodec.MAX_DIMENSION) { "EFBIG: image dimension exceeds the limit" }
        return value
    }

    private fun positiveInt(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

    private fun placementDimension(value: String?): Int? {
        val dimension = positiveInt(value) ?: return null
        require(dimension <= MAX_PLACEMENT_CELLS) { "EFBIG: placement exceeds the cell limit" }
        return dimension
    }

    private fun inflateBounded(compressed: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= RasterCodec.MAX_ENCODED_BYTES) {
                    "EFBIG: inflated payload exceeds the limit"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun readFilePayload(pathBytes: ByteArray, controls: Map<Char, String>, deleteAfterRead: Boolean): ByteArray {
        val pathText = String(pathBytes, StandardCharsets.UTF_8)
        require(pathText.isNotEmpty() && '\u0000' !in pathText) { "EINVAL: invalid image file path" }
        val requestedPath = Path.of(pathText)
        require(requestedPath.isAbsolute) { "EINVAL: image file path must be absolute" }
        val path = try {
            requestedPath.toRealPath()
        } catch (_: Exception) {
            throw IllegalArgumentException("ENOENT: image file was not found")
        }
        require(Files.isRegularFile(path)) { "EINVAL: image path is not a regular file" }
        require(!isSensitivePath(path)) { "EACCES: image path is not allowed" }

        // Kitty's t=f transport is intentionally a local-process capability:
        // it reads a file already accessible to the terminal user's account
        // and never sends those bytes back to the child process. Keep regular-
        // file, virtual-filesystem, range, and size checks here as defense in
        // depth without breaking the protocol's arbitrary-path contract.

        val offset = nonNegativeLong(controls['O'], "offset")
        val requestedSize = nonNegativeLong(controls['S'], "size")
        val result = try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                require(offset <= channel.size()) { "EINVAL: file offset is outside the image file" }
                val remaining = channel.size() - offset
                val bytesToRead = if (requestedSize > 0) requestedSize else remaining
                require(bytesToRead <= remaining) { "EINVAL: requested file range is outside the image file" }
                require(bytesToRead <= RasterCodec.MAX_ENCODED_BYTES) { "EFBIG: image file exceeds the limit" }
                val output = ByteArray(bytesToRead.toInt())
                val buffer = ByteBuffer.wrap(output)
                channel.position(offset)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw IllegalArgumentException("EIO: image file ended early")
                }
                output
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("EIO: image file could not be read")
        } finally {
            if (deleteAfterRead && isSafeTemporaryPath(path)) {
                try {
                    Files.deleteIfExists(path)
                } catch (_: Exception) {
                    // The image was already read successfully; failure to clean up
                    // the producer-owned temporary file is not a protocol failure.
                }
            }
        }
        return result
    }

    private fun nonNegativeLong(value: String?, label: String): Long {
        if (value == null) return 0
        val parsed = value.toLongOrNull()
            ?: throw IllegalArgumentException("EINVAL: invalid file $label")
        require(parsed >= 0) { "EINVAL: invalid file $label" }
        return parsed
    }

    private fun isSensitivePath(path: Path): Boolean {
        val normalized = path.normalize().toString()
        return normalized == "/proc" || normalized.startsWith("/proc/") ||
            normalized == "/sys" || normalized.startsWith("/sys/") ||
            normalized == "/dev" ||
            (normalized.startsWith("/dev/") && !normalized.startsWith("/dev/shm/"))
    }

    private fun isSafeTemporaryPath(path: Path): Boolean {
        if (!path.toString().contains("tty-graphics-protocol")) return false
        val candidates = buildList {
            add(Path.of("/tmp"))
            add(Path.of("/dev/shm"))
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        }
        return candidates.any { temporaryRoot ->
            val normalizedRoot = try {
                temporaryRoot.toRealPath()
            } catch (_: Exception) {
                temporaryRoot.toAbsolutePath().normalize()
            }
            path.startsWith(normalizedRoot)
        }
    }

    private fun appendChunk(destination: StringBuilder, chunk: String) {
        require(destination.length.toLong() + chunk.length <= RasterCodec.MAX_BASE64_CHARS.toLong()) {
            "EFBIG: encoded payload exceeds the limit"
        }
        destination.append(chunk)
    }

    private fun parseCommand(value: String): Command {
        val separator = value.indexOf(';')
        val controlData = if (separator >= 0) value.substring(0, separator) else value
        val payload = if (separator >= 0) value.substring(separator + 1) else ""
        val controls = linkedMapOf<Char, String>()
        if (controlData.isNotEmpty()) {
            for (field in controlData.split(',')) {
                if (field.length < 3 || field[1] != '=') {
                    throw IllegalArgumentException("EINVAL: malformed Kitty control data")
                }
                controls[field[0]] = field.substring(2)
            }
        }
        return Command(controls, payload)
    }

    private fun respond(
        terminal: Terminal?,
        controls: Map<Char, String>,
        success: Boolean,
        message: String = "OK"
    ) {
        if (!responseRequested(controls)) return
        val quiet = controls['q']?.toIntOrNull() ?: 0
        if (quiet == 2 || success && quiet == 1) return
        val identifiers = buildList {
            controls['i']?.let { add("i=$it") }
            controls['I']?.let { add("I=$it") }
            controls['p']?.let { add("p=$it") }
        }
        val response = buildString {
            append(Ascii.ESC)
            append("_G")
            append(identifiers.joinToString(","))
            append(';')
            append(if (success) "OK" else message.take(MAX_RESPONSE_CHARS))
            append(Ascii.ESC)
            append('\\')
        }
        terminal?.deviceStatusReport(response)
    }

    private fun responseRequested(controls: Map<Char, String>): Boolean =
        controls['a'] == "q" || controls.containsKey('i') || controls.containsKey('I')

    private companion object {
        private const val UINT32_MAX = 0xffff_ffffL
        private const val MAX_RESPONSE_CHARS = 256
        private const val MAX_STORED_BYTES = 50 * 1024 * 1024
        private const val MAX_STORED_IMAGES = 100
        private const val MAX_PLACEMENT_CELLS = 4096
    }
}
