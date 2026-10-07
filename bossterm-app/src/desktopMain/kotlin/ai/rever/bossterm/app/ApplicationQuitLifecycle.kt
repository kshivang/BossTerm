package ai.rever.bossterm.app

import java.awt.desktop.QuitResponse

/** Retains the native quit request until application{} has disposed its compositions. */
internal class ApplicationQuitLifecycle {
    @Volatile
    private var pendingResponse: QuitResponse? = null

    fun requestQuit(response: QuitResponse, closeApplication: () -> Unit) {
        pendingResponse = response
        closeApplication()
    }

    fun completeQuit() {
        val response = pendingResponse
        pendingResponse = null
        response?.performQuit()
    }
}
