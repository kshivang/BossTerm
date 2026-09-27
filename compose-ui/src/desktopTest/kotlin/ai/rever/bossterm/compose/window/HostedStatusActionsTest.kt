package ai.rever.bossterm.compose.window

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostedStatusActionsTest {
    @Test
    fun `export existing actions in titlebar order and clear on disposal`() = runTest {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        var exported = emptyList<HostedStatusAction>()
        var clicks = 0
        try {
            composition.setContent {
                HostedStatusActions(onActions = { exported = it }) {
                    for (id in listOf("mcp", "call", "sharing")) {
                        RegisterHostedStatusAction(HostedStatusAction(id, id, "phone", Icons.Default.Call, true) { clicks++ })
                    }
                }
            }
            runCurrent()
            assertEquals(listOf("sharing", "call", "mcp"), exported.map { it.id })
            exported.single { it.id == "call" }.onClick()
            assertEquals(1, clicks)
            composition.dispose()
            assertTrue(exported.isEmpty())
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
