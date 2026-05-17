package ai.rever.bossterm.compose.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Synthetic benchmarks for the BossTerm MCP response-shortening design.
 *
 * We don't have access to a running BossTermMcpServer (no PTY in unit tests),
 * so these tests rebuild the same DTOs and fallback projections in-process,
 * serialize them with the same encoder settings (`explicitNulls = false`),
 * and assert the size-reduction percentages the design plan claims.
 *
 * Targets from `~/.claude/plans/witty-petting-floyd.md`:
 *   - search_output, full → positions-only: ≥70% smaller.
 *   - read_debug_console, full → metadata-only: ≥90% smaller.
 *   - TabInfo with null cwd/pid: presence of "cwd"/"pid" keys before vs after.
 */
class McpResponseShorteningTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val jsonWithNulls = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    // Minimal DTOs that mirror the wire shapes BossTermMcpServer uses. Keeping
    // them local to the test avoids reaching into private state on the server
    // class — the size math is what we're validating, not the wiring.

    @Serializable
    private data class SearchMatchDto(
        val row: Int,
        val line: String,
        val matchStart: Int,
        val matchEnd: Int
    )

    @Serializable
    private data class SearchOutputResultDto(
        val matches: List<SearchMatchDto>,
        val truncated: Boolean,
        val historyLinesCount: Int,
        val height: Int
    )

    @Serializable
    private data class DebugConsoleChunkDto(
        val index: Int,
        val timestamp: Long,
        val source: String,
        val data: String
    )

    @Serializable
    private data class DebugConsoleStatsDto(
        val totalChunks: Int,
        val chunksStored: Int,
        val oldestIndex: Int?,
        val newestIndex: Int?,
        val debugEnabled: Boolean
    )

    @Serializable
    private data class ReadDebugConsoleResultDto(
        val chunks: List<DebugConsoleChunkDto>,
        val stats: DebugConsoleStatsDto
    )

    @Serializable
    private data class TabInfoDto(
        val id: String,
        val title: String,
        val cwd: String?,
        val pid: Long?,
        val isActive: Boolean
    )

    @Test
    fun searchOutputFullVsPositionsOnly() {
        // Representative: 200 matches each on a line ~80 chars long.
        val lineText = "the quick brown fox jumps over the lazy dog ".repeat(2).take(80)
        val matches = (0 until 200).map { i ->
            SearchMatchDto(row = i - 50, line = lineText, matchStart = 0, matchEnd = 3)
        }
        val full = SearchOutputResultDto(
            matches = matches,
            truncated = false,
            historyLinesCount = 1000,
            height = 24
        )
        val fullJson = json.encodeToString(SearchOutputResultDto.serializer(), full)

        // Same shape as the BossTermMcpServer search_output first fallback.
        val positionsOnly = buildJsonObject {
            put(
                "matches",
                buildJsonArray {
                    for (m in matches) add(
                        buildJsonObject {
                            put("row", m.row)
                            put("matchStart", m.matchStart)
                            put("matchEnd", m.matchEnd)
                        }
                    )
                }
            )
            put("truncated", false)
            put("historyLinesCount", 1000)
            put("height", 24)
            put("shortened", "matches: positions only (no line text)")
        }.toString()

        val reduction = 100 - (positionsOnly.length * 100 / fullJson.length)
        println("[search_output] full=${fullJson.length}B positions=${positionsOnly.length}B reduction=$reduction%")
        assertTrue(
            reduction >= 70,
            "expected ≥70% reduction, got $reduction% (full=${fullJson.length}, positions=${positionsOnly.length})"
        )
    }

    @Test
    fun readDebugConsoleFullVsMetadataOnly() {
        // Representative: 800 chunks each ~512 bytes of binary-ish data.
        val data = "x".repeat(512)
        val chunks = (0 until 800).map { i ->
            DebugConsoleChunkDto(
                index = i,
                timestamp = 1_700_000_000_000L + i,
                source = if (i % 2 == 0) "PTY_OUTPUT" else "USER_INPUT",
                data = data
            )
        }
        val stats = DebugConsoleStatsDto(
            totalChunks = 9999, chunksStored = 800,
            oldestIndex = 9200, newestIndex = 9999,
            debugEnabled = true
        )
        val full = ReadDebugConsoleResultDto(chunks = chunks, stats = stats)
        val fullJson = json.encodeToString(ReadDebugConsoleResultDto.serializer(), full)

        // Same shape as the BossTermMcpServer read_debug_console first fallback.
        val metadataOnly = buildJsonObject {
            put(
                "chunks",
                buildJsonArray {
                    for (c in chunks) add(
                        buildJsonObject {
                            put("index", c.index)
                            put("timestamp", c.timestamp)
                            put("source", c.source)
                        }
                    )
                }
            )
            put("stats", json.encodeToJsonElement(DebugConsoleStatsDto.serializer(), stats))
            put("shortened", "chunks: metadata only (data omitted)")
        }.toString()

        val reduction = 100 - (metadataOnly.length * 100 / fullJson.length)
        println("[read_debug_console] full=${fullJson.length}B metadata=${metadataOnly.length}B reduction=$reduction%")
        assertTrue(
            reduction >= 90,
            "expected ≥90% reduction, got $reduction% (full=${fullJson.length}, metadata=${metadataOnly.length})"
        )
    }

    @Test
    fun explicitNullsOmissionForTabInfoNullFields() {
        // A tab with no shell-integration cwd and no pid yet.
        val tab = TabInfoDto(
            id = "abc-123", title = "Shell 1", cwd = null, pid = null, isActive = true
        )
        val newWire = json.encodeToString(TabInfoDto.serializer(), tab)
        val oldWire = jsonWithNulls.encodeToString(TabInfoDto.serializer(), tab)

        println("[TabInfo null cwd+pid] withNulls=${oldWire.length}B omitted=${newWire.length}B")

        // New wire shouldn't carry the keys at all.
        assertFalse(newWire.contains("\"cwd\""), "expected cwd key omitted, got: $newWire")
        assertFalse(newWire.contains("\"pid\""), "expected pid key omitted, got: $newWire")
        // Old wire did carry them.
        assertTrue(oldWire.contains("\"cwd\":null"), "explicitNulls=true should emit cwd:null")
        assertTrue(oldWire.contains("\"pid\":null"), "explicitNulls=true should emit pid:null")
        // Real, non-null fields are still there.
        assertTrue(newWire.contains("\"id\":\"abc-123\""))
        assertTrue(newWire.contains("\"isActive\":true"))
    }

    @Test
    fun explicitNullsKeepsNonNullValues() {
        // Sanity: a TabInfo with real values is unchanged in keys.
        val tab = TabInfoDto(
            id = "abc-123", title = "Shell 1", cwd = "/tmp", pid = 12345L, isActive = true
        )
        val wire = json.encodeToString(TabInfoDto.serializer(), tab)
        // All five fields should be present.
        for (key in listOf("\"id\"", "\"title\"", "\"cwd\"", "\"pid\"", "\"isActive\"")) {
            assertTrue(wire.contains(key), "expected $key in $wire")
        }
    }

    @Test
    fun searchOutputFullVsTotalsOnly() {
        // The deepest fallback: totals only. Sanity-check it's tiny.
        val lineText = "x".repeat(80)
        val matches = (0 until 100).map { i ->
            SearchMatchDto(row = i, line = lineText, matchStart = 0, matchEnd = 1)
        }
        val full = SearchOutputResultDto(matches, false, 0, 24)
        val fullJson = json.encodeToString(SearchOutputResultDto.serializer(), full)

        val totalsOnly = buildJsonObject {
            put("totalMatches", matches.size)
            put("truncated", false)
            put("historyLinesCount", 0)
            put("height", 24)
            put("shortened", "totals only")
        }.toString()

        println("[search_output totals] full=${fullJson.length}B totals=${totalsOnly.length}B")
        // Totals dwarfs nothing; assert it's <500 bytes regardless of input size.
        assertTrue(totalsOnly.length < 500, "totals form should be <500B, got ${totalsOnly.length}")
    }
}
