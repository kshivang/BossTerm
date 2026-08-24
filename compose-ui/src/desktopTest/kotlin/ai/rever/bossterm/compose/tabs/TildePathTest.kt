package ai.rever.bossterm.compose.tabs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TildePathTest {

    private val home = "/Users/alice"

    @Test
    fun `a path under home folds to a tilde`() {
        assertEquals("~/Development/Boss", tildePath("/Users/alice/Development/Boss", home))
    }

    @Test
    fun `home itself folds to a bare tilde`() {
        assertEquals("~", tildePath("/Users/alice", home))
        assertEquals("~", tildePath("/Users/alice/", home))
    }

    @Test
    fun `a sibling that merely starts with home is left intact`() {
        // The "$home/" guard, not a bare prefix test: this must not become "~-backup".
        assertEquals("/Users/alice-backup", tildePath("/Users/alice-backup", home))
        assertEquals("/Users/alice-backup/src", tildePath("/Users/alice-backup/src", home))
    }

    @Test
    fun `a trailing slash is trimmed but the root survives it`() {
        assertEquals("/etc/nginx", tildePath("/etc/nginx/", home))
        assertEquals("/", tildePath("/", home))
    }

    @Test
    fun `a null or empty home folds nothing`() {
        // What a mirrored pane passes: the cwd belongs to the remote host, so folding the
        // LOCAL home would turn a same-named remote user's /Users/alice/proj into ~/proj.
        assertEquals("/Users/alice/proj", tildePath("/Users/alice/proj", null))
        assertEquals("/Users/alice/proj", tildePath("/Users/alice/proj", ""))
    }

    @Test
    fun `home is matched with its own trailing slash trimmed`() {
        assertEquals("~/proj", tildePath("/Users/alice/proj", "/Users/alice/"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(tildePath(null, home))
        assertNull(tildePath("", home))
        assertNull(tildePath("   ", home))
    }
}
