package ai.rever.bossterm.compose.debug

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.Writer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingWriteFailureTest {
    private class FailingWriter : Writer() {
        var failWrites = false
        var failClose = false
        var closed = false
        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (failWrites) throw IOException("simulated full disk")
        }
        override fun flush() {}
        override fun close() {
            closed = true
            if (failClose) throw IOException("simulated close failure")
        }
    }

    @Test
    fun chunkFailureStopsRecordingAndAllowsRestart() {
        val collector = DebugDataCollector(null)
        val sink = FailingWriter()
        val file = File.createTempFile("recording-failure", ".log")
        try {
            collector.startFileLogging(file.path) { PrintWriter(sink, true) }
            assertTrue(collector.isFileLoggingActive())
            sink.failWrites = true
            collector.recordChunk("lost output", ChunkSource.PTY_OUTPUT)
            assertFalse(collector.isFileLoggingActive())
            assertNull(collector.getLogFilePath())
            assertTrue(sink.closed)
            collector.recordChunk("later output", ChunkSource.PTY_OUTPUT)

            collector.startFileLogging(file.path)
            collector.recordChunk("recovered", ChunkSource.PTY_OUTPUT)
            collector.stopFileLogging()
            assertTrue(file.readText().contains("recovered"))
        } finally {
            collector.stopFileLogging()
            file.delete()
        }
    }

    @Test
    fun headerFailureDoesNotLeaveRecordingActive() {
        val collector = DebugDataCollector(null)
        val sink = FailingWriter().apply { failWrites = true }
        val file = File.createTempFile("recording-header", ".log")
        try {
            collector.startFileLogging(file.path) { PrintWriter(sink, true) }
            assertFalse(collector.isFileLoggingActive())
            assertNull(collector.getLogFilePath())
            assertTrue(sink.closed)
        } finally {
            collector.stopFileLogging()
            file.delete()
        }
    }

    @Test
    fun closeFailureStillClearsRecordingState() {
        val collector = DebugDataCollector(null)
        val sink = FailingWriter().apply { failClose = true }
        val file = File.createTempFile("recording-close", ".log")
        try {
            collector.startFileLogging(file.path) { PrintWriter(sink, true) }
            collector.stopFileLogging()
            assertFalse(collector.isFileLoggingActive())
            assertNull(collector.getLogFilePath())
            assertTrue(sink.closed)
        } finally {
            collector.stopFileLogging()
            file.delete()
        }
    }
}
