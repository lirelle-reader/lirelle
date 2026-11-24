package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The settings of the reader: read for the album from the store of its profile, and written there as the user
 * chooses, in the sheet or with the button of reading panel by panel.
 */
internal class SettingsPart(
    private val core: ComicCore,
    private val store: ComicSettingsStore,
    private val bars: Bars,
) : ComicSettingsActions {
    private var following: Job? = null

    /**
     * Reads the settings of an album whose direction is kept for [scope], then follows them: the album
     * opens with them, and the spreads follow the two pages' setting. Its direction is [default] (its
     * server's) as long as the user chose none for [scope].
     */
    suspend fun follow(scope: DirectionScope, default: Direction?) {
        following?.cancel()
        val settings = store.settings(scope, default)
        val kept = settings.first()
        core.update { it.copy(settings = kept) }
        following = core.scope.launch {
            settings.collect { settings ->
                core.update { it.copy(settings = settings) }
                core.layout()
            }
        }
    }

    override fun setFit(fit: Fit) = change { store.setFit(fit) }

    override fun setTwoPages(twoPages: TwoPages) = change { store.setTwoPages(twoPages) }

    override fun setBackground(background: Background) = change { store.setBackground(background) }

    override fun setDirection(direction: Direction) = change {
        core.current.directionScope?.let { store.setDirection(it, direction) }
    }

    override fun setWholePageFirst(first: Boolean) = change { store.setWholePageFirst(first) }

    /** Reading panel by panel, on or off: for the series of the album, or for the album alone when it has none. */
    fun setPanels(on: Boolean) = change {
        core.current.directionScope?.let { store.setPanels(it, on) }
    }

    /** The user is choosing: the bars no longer hide by themselves. */
    private fun change(write: suspend () -> Unit) {
        bars.stay()
        core.scope.launch { write() }
    }
}
