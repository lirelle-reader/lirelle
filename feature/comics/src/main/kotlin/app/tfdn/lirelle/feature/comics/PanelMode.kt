package app.tfdn.lirelle.feature.comics

import android.util.Log
import app.tfdn.lirelle.core.comics.panels.PagePanels
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.layout.sideways
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.feature.comics.page.wholeReason
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val Tag = "Panels"

/**
 * Reading panel by panel, in the reader: the state says whether the album can be read so, whether its series is,
 * and what is known of the pages near. The album's panels are told how it is read and where the reader is; its
 * pages are cut while the series is read panel by panel, or while a build that can be debugged asks to see the
 * zones, and what is known of the pages of the spread shown and of the spreads before and after it is put in the
 * state as it comes, cut or read whole. Otherwise nothing is cut, watched nor written in the state.
 *
 * The first time the mode is turned on on the device, what it is is told before it is turned on. An album of which
 * few pages could be cut says so once ([AlbumTelling]).
 */
internal class PanelMode(
    private val core: ComicCore,
    private val settings: SettingsPart,
    private val bars: Bars,
    private val store: ComicSettingsStore,
) : PanelModeActions {
    private val telling = AlbumTelling(core, store)
    private val watched = mutableMapOf<Int, Job>()

    /** What the panels were last told: they are told again only what changed. */
    private var direction: Direction? = null
    private var shown: Pair<Int, Int>? = null
    private var cutting = false

    /**
     * What was last logged of each page: a page shown again with the same answer is not logged again. Logged in
     * a build that can be debugged only, where the log times the cuts on a device.
     */
    private val logged = mutableMapOf<Int, PageZones>()

    /**
     * The choice is written with the settings: the state follows what is kept, as it does for them. On a device
     * that was never told what the mode is, turning it on tells it first and writes nothing yet.
     */
    override fun setPanelMode(on: Boolean) {
        if (!on) {
            telling.close()
            return settings.setPanels(false)
        }
        bars.stay()
        core.scope.launch {
            if (store.panelNoticeSeen.first()) settings.setPanels(true) else tellNotice(true)
        }
    }

    override fun closePanelNotice() {
        if (!core.current.panels.notice) return
        tellNotice(false)
        core.scope.launch { store.seePanelNotice() }
        settings.setPanels(true)
    }

    override fun closeAlbumBanner() = telling.close()

    private fun tellNotice(told: Boolean) = core.update { it.copy(panels = it.panels.copy(notice = told)) }

    override fun showZones(shown: Boolean) {
        if (shown == core.current.zonesShown) return
        core.update { it.copy(zonesShown = shown) }
        refresh()
    }

    /**
     * The spreads, the one shown or the settings may have changed. To be called after the reader asked to watch
     * the pages of the spread shown: the panels cut first the page they are told, from what is watched of it.
     */
    fun refresh() {
        val panels = core.panels ?: return
        if (core.current.spread == null) return
        follow(panels)
        // In a coroutine, as the watches of the pages are: when those wait for what runs now to be over, this
        // waits behind them, and the page is watched by the time the panels are told of it.
        core.scope.launch { tell(panels) }
        watch(panels)
        telling.count()
    }

    /**
     * The mode is what the settings say of it, for an album whose pages can be cut and are turned: a strip read
     * vertically has no panel to go to. Only what changed.
     */
    private fun follow(panels: PagePanels) {
        val state = core.current
        val offered = panels.cuts && !state.vertical
        val on = offered && state.settings.panels
        val reading = state.panels.copy(offered = offered, on = on, wholePageFirst = state.settings.wholePageFirst)
        if (reading != state.panels) core.update { it.copy(panels = reading) }
    }

    /**
     * How the album is read, then where the reader is, then whether its pages are cut: zones are those of a
     * direction, and the page shown is the first cut. Only what changed. The panels know only pages turned: they
     * are never told a vertical direction.
     */
    private fun tell(panels: PagePanels) {
        val state = core.current
        val direction = state.settings.direction.sideways()
        val shown = (state.spread ?: return).first to state.pageCount
        if (direction != this.direction) {
            this.direction = direction
            panels.direction(direction)
        }
        if (shown != this.shown) {
            this.shown = shown
            panels.show(shown.first, shown.second)
        }
        if (state.cutting != cutting) {
            cutting = state.cutting
            panels.cutting(cutting)
        }
    }

    /** Collects what is known of the pages near while they are cut, and of none otherwise. */
    private fun watch(panels: PagePanels) {
        val state = core.current
        val near = if (state.cutting) state.near() else emptySet()
        if (near.isEmpty() && watched.isEmpty()) return
        (watched.keys - near).forEach { watched.remove(it)?.cancel() }
        core.update { it.withZones(it.panels.zones.filterKeys(near::contains)) }
        // Each is in the map before it runs, as the pages watched are: zones already known come at once.
        val added = (near - watched.keys).map { index ->
            core.scope.launch(start = CoroutineStart.LAZY) { panels.zones(index).collect { known(index, it) } }
                .also { watched[index] = it }
        }
        added.forEach { it.start() }
    }

    private fun known(index: Int, zones: PageZones) {
        if (index !in watched) return
        if (zones == PageZones.Unknown) {
            core.update { it.withZones(it.panels.zones - index) }
            return
        }
        core.update { it.withZones(it.panels.zones + (index to zones)) }
        telling.count()
        if (core.debuggable && logged.put(index, zones) != zones) Log.d(Tag, "page $index: ${said(zones)}")
    }
}

/** Whether the pages are cut: the series is read panel by panel, or the zones are asked to be seen. */
private val ComicReaderUiState.cutting: Boolean get() = panels.on || zonesShown

/** The pages of the spread shown and of the spreads before and after it. */
private fun ComicReaderUiState.near(): Set<Int> =
    (spreadIndex - 1..spreadIndex + 1).flatMap { spreads.getOrNull(it)?.pages.orEmpty() }.toSet()

private fun ComicReaderUiState.withZones(zones: Map<Int, PageZones>) = copy(panels = panels.copy(zones = zones))

/** What the log says of a page that is cut, or read whole. */
private fun said(zones: PageZones): String = when (zones) {
    is PageZones.Cut -> "${zones.zones.size} zones in ${zones.millis} ms"
    is PageZones.Whole -> "whole (${wholeReason(zones.why)})"
    PageZones.Unknown -> "not known"
}
