package ai.rever.bossterm.compose.remote

import ai.rever.bossterm.compose.ComposeTerminalDisplay
import ai.rever.bossterm.compose.share.*
import ai.rever.bossterm.terminal.model.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class RemotePaneGraphicsTest {
    @Test fun `full graphics and deltas update cache placements and clear without changing text`() {
        val display = ComposeTerminalDisplay()
        val style = StyleState()
        val buffer = TerminalTextBuffer(80, 24, style)
        val terminal = BossTerminal(display, buffer, style)
        val graphics = RemotePaneGraphics()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
        val image = SharedTerminalImage("42", "image/png", Base64.getEncoder().encodeToString(bytes), "hash")
        val cell = SharedImageCellRun("42", 0, 2, 0, 0, 2, 2, 1)
        val initial = ServerMessage.PaneGraphics("pane", 1, true, listOf(image), requiredImageIds = listOf("42"), cells = listOf(cell), historyLines = 0)
        try {
            val text = buffer.getScreenLines()
            assertTrue(graphics.apply(initial, terminal, buffer))
            assertEquals(42L, buffer.getLine(0).getImageCellAt(2)?.imageId)
            assertTrue(terminal.getImageDataCache().hasImage(42))
            assertEquals(text, buffer.getScreenLines())
            assertTrue(graphics.apply(initial.copy(revision = 2, full = false, images = emptyList(), cells = listOf(cell.copy(row = 1))), terminal, buffer))
            assertNull(buffer.getLine(0).getImageCellAt(2))
            assertEquals(42L, buffer.getLine(1).getImageCellAt(2)?.imageId)
            assertFalse(graphics.apply(initial.copy(revision = 4, full = false), terminal, buffer), "revision gap needs a full snapshot")
            assertEquals(42L, buffer.getLine(1).getImageCellAt(2)?.imageId)
            assertTrue(graphics.apply(ServerMessage.PaneGraphics("pane", 3, false, removedImageIds = listOf("42")), terminal, buffer))
            assertFalse(terminal.getImageDataCache().hasImage(42))
            assertNull(buffer.getLine(1).getImageCellAt(2))
            assertTrue(graphics.apply(initial.copy(revision = 0), terminal, buffer), "new connection's full snapshot replaces an old revision")
        } finally { display.dispose() }
    }
}
