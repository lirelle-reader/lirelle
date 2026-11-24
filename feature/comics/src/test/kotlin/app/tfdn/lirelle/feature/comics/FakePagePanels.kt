package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.panels.PagePanels
import app.tfdn.lirelle.core.comics.panels.PagePanelsOpener
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.settings.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** The zones of an album, said by the test; remembers what the reader told, in order. */
internal class FakePagePanels : PagePanels {
    private val states = mutableMapOf<Int, MutableStateFlow<PageZones>>()

    val calls = mutableListOf<String>()

    override var cuts = true

    /** For each page told shown, whether the reader watched what the device has of it at that moment. */
    val watchedWhenShown = mutableListOf<Boolean>()

    /** The pages the zones are of, once the album is open. */
    var pages: ComicPages? = null

    var closed = false
        private set

    /** The page [index] was cut into [zones], in [millis]. */
    fun cut(index: Int, zones: List<Zone>, millis: Long = 0) {
        zones(index).value = PageZones.Cut(zones, millis)
    }

    /** The page [index] is read whole, and [why]. */
    fun whole(index: Int, why: WholePage) {
        zones(index).value = PageZones.Whole(why)
    }

    /** Nothing is known of the page [index] any more. */
    fun forget(index: Int) {
        zones(index).value = PageZones.Unknown
    }

    /** How many watch the zones of the page [index]. */
    fun watchers(index: Int): Int = zones(index).subscriptionCount.value

    override fun zones(index: Int): MutableStateFlow<PageZones> =
        states.getOrPut(index) { MutableStateFlow(PageZones.Unknown) }

    override fun cutting(on: Boolean) {
        calls += "cutting $on"
    }

    override fun show(index: Int, pageCount: Int) {
        calls += "show $index $pageCount"
        val state = pages?.page(index) as? MutableStateFlow<*>
        watchedWhenShown += (state?.subscriptionCount?.value ?: 0) > 0
    }

    override fun direction(direction: Direction) {
        calls += "direction $direction"
    }

    override fun close() {
        closed = true
    }
}

/** Opens the same [panels] for every album, and remembers for which. */
internal class FakePagePanelsOpener(val panels: FakePagePanels = FakePagePanels()) : PagePanelsOpener {
    val opened = mutableListOf<ComicKey>()

    override fun open(key: ComicKey, pages: ComicPages, scope: CoroutineScope): PagePanels {
        opened += key
        panels.pages = pages
        return panels
    }
}
