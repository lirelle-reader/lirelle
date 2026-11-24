package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.settings.BrightnessRange
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import app.tfdn.lirelle.feature.reader.bridge.BrightnessPhase
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The reading settings, the theme they resolve to with the device, and what the page is told of them.
 *
 * @param lasting where a brightness set by hand is stored: it outlives the view model, so that
 *   what is stored as the reader is left is stored all the same.
 */
internal class Appearance(
    private val core: ReaderCore,
    private val store: ReaderSettingsStore,
    private val lasting: CoroutineScope,
) {

    /** Completed once the stored settings are known: a book is not opened with the default ones. */
    val loaded = CompletableDeferred<Unit>()

    /** The brightness on screen was set by hand and is not stored yet: what the store says does not replace it. */
    private var brightnessUnsaved = false

    fun start() {
        core.scope.launch {
            store.settings.collect {
                apply(it)
                loaded.complete(Unit)
            }
        }
    }

    /** A brightness still waiting is stored first: [transform] is given the settings the user sees. */
    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        keepBrightness()
        core.scope.launch { store.update(transform) }
    }

    /**
     * The brightness while a finger moves it. It is on screen at once and nothing is written: a
     * write for every move would have the screen wait for the disk. [keepBrightness] stores it.
     */
    fun setBrightness(level: Float) {
        if (level.isNaN()) return
        brightnessUnsaved = true
        core.update { it.copy(settings = it.settings.copy(brightness = level.coerceIn(BrightnessRange))) }
    }

    /** The finger is lifted, or the reader left: the brightness on screen is stored, if it was not yet. */
    fun keepBrightness() {
        if (!brightnessUnsaved) return
        brightnessUnsaved = false
        val brightness = core.current.settings.brightness
        lasting.launch { store.update { it.copy(brightness = brightness) } }
    }

    fun onSystemDark(dark: Boolean) {
        core.systemDark = dark
        apply(core.current.settings)
    }

    fun onWindow(wide: Boolean) {
        core.wide = wide
        core.pushSettings()
    }

    /** A finger slides along the brightness edge: the first move starts from the window's own brightness. */
    fun onBrightness(message: PageMessage.Brightness) {
        when (message.phase) {
            BrightnessPhase.Start -> core.update { it.copy(adjustingBrightness = true) }
            BrightnessPhase.End -> core.update { it.copy(adjustingBrightness = false) }
            BrightnessPhase.Move -> Unit
        }
        if (message.delta != 0.0) {
            val from = core.current.settings.brightness ?: core.current.systemBrightness
            setBrightness(from + message.delta.toFloat())
        }
        if (message.phase == BrightnessPhase.End) keepBrightness()
    }

    private fun apply(settings: ReaderSettings) {
        val shown = if (brightnessUnsaved) settings.copy(brightness = core.current.settings.brightness) else settings
        core.update { it.copy(settings = shown, colours = shown.theme.colours(core.systemDark)) }
        core.pushSettings()
    }
}
