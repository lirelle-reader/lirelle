package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import kotlinx.coroutines.launch

/**
 * An album that lends itself badly to reading panel by panel says so: the pages shown while the series is read so
 * are counted as what is known of them comes, and when few of them could be cut the state says it
 * ([PanelReading.albumBanner]). Once for the album: the [store] remembers it from the moment the user answered,
 * at this opening and at the next. An album that said it to nobody says it again at another opening.
 */
internal class AlbumTelling(private val core: ComicCore, private val store: ComicSettingsStore) {
    /** Of this opening of the album. */
    private val tally = AlbumTally()

    /** The spread shown, or what is known of its pages, may have changed: its pages are counted. */
    fun count() {
        val state = core.current
        if (!state.panels.on) return
        val badly = state.spread?.pages.orEmpty().map { tally.see(it, state.panels.zones[it]) }.any { it }
        if (badly) core.scope.launch { tell() }
    }

    /**
     * What the album said was answered, or reading panel by panel was turned off: it is said no longer. The album
     * is remembered as told when the user could see it said: not while the offer of a newer page had its place.
     */
    fun close() {
        val state = core.current
        if (!state.panels.albumBanner) return
        show(false)
        if (state.newerPage == null) core.scope.launch { store.tellAlbum(core.bookId) }
    }

    /** Asking the store takes time: a user who left the mode meanwhile is told nothing. */
    private suspend fun tell() {
        if (store.albumTold(core.bookId)) return
        if (core.current.panels.on) show(true)
    }

    private fun show(shown: Boolean) = core.update { it.copy(panels = it.panels.copy(albumBanner = shown)) }
}
