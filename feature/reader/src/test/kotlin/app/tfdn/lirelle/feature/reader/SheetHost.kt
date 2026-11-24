package app.tfdn.lirelle.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.normalized
import java.lang.reflect.Proxy

/**
 * Stands for the view model under the sheets: it applies what the sheets ask for to [state], as
 * the reader does, so a test reads the outcome there. Anything else the reader can be asked is
 * ignored: the sheets have no business asking for it.
 */
class SheetHost(sheet: ReaderSheet?, settings: ReaderSettings = ReaderSettings(), systemBrightness: Float? = null) {
    var state by mutableStateOf(
        ReaderUiState(settings = settings, openSheet = sheet).let { state ->
            if (systemBrightness == null) state else state.copy(systemBrightness = systemBrightness)
        },
    )
        private set

    val settings: ReaderSettings get() = state.settings

    /** How many times the settings were changed. */
    var updates = 0
        private set

    /** Each brightness set under a finger, then how many times it was said to be kept. */
    val brightnessSet = mutableListOf<Float>()
    var brightnessKept = 0
        private set

    @Suppress("UNCHECKED_CAST")
    val actions: ReaderActions = Proxy.newProxyInstance(
        ReaderActions::class.java.classLoader,
        arrayOf(ReaderActions::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            // Compose compares and hashes what it is given.
            "equals" -> return@newProxyInstance proxy === arguments[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "SheetHost.actions"
            "updateSettings" -> {
                val transform = arguments[0] as (ReaderSettings) -> ReaderSettings
                updates++
                state = state.copy(settings = transform(state.settings).normalized())
            }
            "setBrightness" -> {
                brightnessSet += arguments[0] as Float
                state = state.copy(settings = state.settings.copy(brightness = arguments[0] as Float).normalized())
            }
            "keepBrightness" -> brightnessKept++
            "showSheet" -> state = state.copy(openSheet = arguments[0] as ReaderSheet)
            "dismissSheet" -> state = state.copy(openSheet = null)
        }
        null
    } as ReaderActions
}
