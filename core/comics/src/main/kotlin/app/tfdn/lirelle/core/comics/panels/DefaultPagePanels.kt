package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.fetch.windowOrder
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.library.model.PagedKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Cuts with the cut itself, on the picture decoded for it alone: the picture is let go as soon as its zones are
 * found. The pages of a PDF album are not cut: such an album is not read panel by panel.
 *
 * @param store where the zones found are kept.
 * @param work where the cuts run.
 */
internal class DefaultPagePanelsOpener(
    private val store: ZoneStore,
    private val work: CoroutineDispatcher,
) : PagePanelsOpener {
    override fun open(key: ComicKey, pages: ComicPages, scope: CoroutineScope): PagePanels =
        if (key.kind == PagedKind.Pdf) {
            NoPagePanels
        } else {
            DefaultPagePanels(key, pages, store, scope, work) { file, direction ->
                when (val decoding = decodeForCut(file)) {
                    is PageDecoding.Picture -> CutOutcome.Zones(cutPage(decoding.pixels, direction))
                    PageDecoding.NoPicture -> CutOutcome.NotCut
                    PageDecoding.NotNow -> CutOutcome.Later
                }
            }
        }
}

/** The zones of an album whose pages are not cut: none is ever known, whatever is asked. */
private object NoPagePanels : PagePanels {
    private val unknown: StateFlow<PageZones> = MutableStateFlow(PageZones.Unknown)

    override val cuts: Boolean = false

    override fun zones(index: Int): StateFlow<PageZones> = unknown

    override fun cutting(on: Boolean) = Unit

    override fun show(index: Int, pageCount: Int) = Unit

    override fun direction(direction: Direction) = Unit

    override fun close() = Unit
}

/** What comes of cutting a page. */
internal sealed interface CutOutcome {
    /** The cut was done: its [zones] are kept, and shown when they are panels (two or more). */
    data class Zones(val zones: List<Zone>) : CutOutcome

    /** The page's file is no picture the cut handles, and never will be: the page is read whole, and that is kept. */
    data object NotCut : CutOutcome

    /**
     * The page could not be cut this time (no room for its picture, its file gone): it is read whole for now, and
     * nothing is kept, so it is cut the next time the album is read.
     */
    data object Later : CutOutcome
}

/** What the cuts are told, by the reader or by a page that is watched. Handled one at a time, in their order. */
private sealed interface Ask {
    data class Cutting(val on: Boolean) : Ask

    data class Show(val index: Int, val pageCount: Int) : Ask

    data class Way(val direction: Direction) : Ask

    /** The page says what the device has of it: its picture's [file], or null when it has none. */
    data class Told(val index: Int, val file: File?) : Ask
}

/** The first page of an album: its cover, which is not cut. */
private const val Cover = 0

/** The fewest zones that make a page of panels: one zone, or none, is the page itself. */
private const val FewestPanels = 2

/**
 * What a page whose cut gave [zones] is: cut, or read whole when they are too few to be panels. Zones kept from
 * before are told the same way, with no time: a file that was no picture is kept with no zone, and reads back as
 * a page with no panels.
 */
private fun pageZones(zones: List<Zone>, millis: Long): PageZones =
    if (zones.size < FewestPanels) PageZones.Whole(WholePage.NoPanels) else PageZones.Cut(zones, millis)

/** A page to cut, and the [file] its picture is in. */
private class Picture(val index: Int, val file: File)

/**
 * The pages looked at for their pictures: those of the window around the page shown. Looking at a page asks
 * nothing of the server: the page tells what the device has of it, now and as it changes.
 *
 * @param asks where a page says what the device has of it.
 */
private class Watched(private val pages: ComicPages, private val asks: SendChannel<Ask>) {
    private val watchers = mutableMapOf<Int, Job>()

    /** What each page said last; a page that said nothing yet is not there. */
    private val told = mutableMapOf<Int, File?>()

    /** The pages of [window] are looked at, in [scope]; the others no longer, and what they said is forgotten. */
    fun watch(window: List<Int>, scope: CoroutineScope) {
        (watchers.keys - window.toSet()).forEach {
            watchers.remove(it)?.cancel()
            told.remove(it)
        }
        window.filter { it !in watchers }.forEach { index ->
            watchers[index] = scope.launch { listen(index) }
        }
    }

    /**
     * Tells what the page [index] says of its picture, as it changes. A page that fails to say is one whose
     * picture is not on the device, and is no longer listened to: that is not to stop the cuts, nor the reader.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun listen(index: Int) {
        try {
            pages.page(index).collect { asks.trySend(Ask.Told(index, (it as? PageState.Ready)?.file)) }
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
            asks.trySend(Ask.Told(index, null))
        }
    }

    /** A page no longer looked at may still have said something on its way out: it is not heard. */
    fun told(index: Int, file: File?) {
        if (index in watchers) told[index] = file
    }

    /**
     * The page to cut next: the first of [window], in its order, that is not [done] and whose picture is known to
     * be on the device. A page that has not said yet what the device has of it is waited for, null meanwhile: every
     * page says so as soon as it is looked at, so the wait is short, and without it a page further in the window,
     * which happened to speak first, would be cut before the page shown. A page that said its picture is not there
     * is passed over: it is cut when it comes, in its turn.
     *
     * A page says what is known of it at that moment: one nobody looked at yet in this opening of the album may
     * say first that it has no picture, and only then, once the device was asked, that it has one. Such a page is
     * passed over that once, and cut after the page that took its turn. So the page shown is the first cut when
     * its picture is known to be there, which it is when the reader looks at the page it shows before it says so.
     */
    fun next(window: List<Int>, done: Set<Int>): Picture? {
        val index = window.firstOrNull { it !in done && (it !in told || told[it] != null) }
        return index?.let { page -> told[page]?.let { Picture(page, it) } }
    }
}

/**
 * What the store keeps of one album, read once per direction, when first needed. A store that fails is one that
 * keeps nothing: the zones are only kept to spare a cut, and that is not to stop the cuts, nor the reader.
 */
private class Kept(private val store: ZoneStore, private val key: ComicKey) {
    private var album: Map<Int, KeptZones>? = null

    /** The direction changed: what was read is of the other one. */
    fun forget() {
        album = null
    }

    /** The zones kept of the page [index] read in [direction], for a picture of [bytes]; null when there are none. */
    fun zones(direction: Direction, index: Int, bytes: Long): List<Zone>? {
        val read = album ?: read(direction).also { album = it }
        return read[index]?.takeIf { it.pictureBytes == bytes }?.zones
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read(direction: Direction): Map<Int, KeptZones> = try {
        store.of(key, direction)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        emptyMap()
    }

    /** Keeps the [zones] of the page [index]; when the store fails they are not kept, and found again later. */
    @Suppress("TooGenericExceptionCaught")
    fun keep(direction: Direction, index: Int, bytes: Long, zones: List<Zone>) {
        try {
            store.keep(key, direction, index, bytes, zones)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
            // Not kept: the page is cut again the next time.
        }
    }
}

/**
 * What the reader says is put in a line, with what the pages looked at say of their pictures, and one coroutine of
 * [scope] takes it from there, one thing at a time: everything here but [published] is that coroutine's alone.
 * Between two things said it cuts one page, so what the reader said during a cut is obeyed before the next one,
 * and two pages are never cut at once: a cut holds tens of megabytes.
 *
 * A cut does not wait, and the pages looked at speak on the same single thread: before each thing it does, the
 * coroutine steps aside for them, so a picture that arrived during a cut is heard before the next page is
 * chosen. That is also where it sees that the reader closed: no other page is cut then, and what the cut under
 * way finds is neither kept nor shown.
 *
 * While the cutting is on, the pages of the fetch window around the page shown are looked at, and the first of
 * them, in the window's order, whose picture is known to be on the device and that is not cut yet, is the next
 * (see [Watched.next]). Zones kept from before, for a picture of the same length, are given without a cut. A page
 * whose cut gives fewer than two zones is read whole, and so is one whose file is no picture; both are kept. A cut
 * that throws, or that could not be done this time ([CutOutcome.Later]), leaves its page whole too, as failed, but
 * is not kept: it is tried again the next time the album is read.
 *
 * The first page, the cover, is told read whole as soon as it is in the window: it is not looked at, nothing of it is
 * read from the store or kept there, and the other pages do not wait for it.
 *
 * Nothing is asked of [pages] but what the device has of a page: no page is fetched, shown or drawn for its zones.
 *
 * @param cutter what comes of cutting the picture in a file read in a direction.
 * @param work where the cuts run, one at a time.
 */
internal class DefaultPagePanels(
    key: ComicKey,
    pages: ComicPages,
    store: ZoneStore,
    scope: CoroutineScope,
    work: CoroutineDispatcher,
    private val cutter: suspend (File, Direction) -> CutOutcome,
) : PagePanels {
    private val asks = Channel<Ask>(Channel.UNLIMITED)
    private val published = ConcurrentHashMap<Int, MutableStateFlow<PageZones>>()
    private val watched = Watched(pages, asks)

    private var on = false
    private var shown: Ask.Show? = null
    private var direction = Direction.LeftToRight

    /** The pages cut, or given from the store, in the direction in use. */
    private val done = mutableSetOf<Int>()

    /** What the store keeps of the album, in the direction in use. */
    private val kept = Kept(store, key)

    override val cuts: Boolean = true

    private val job: Job = scope.launch(work.limitedParallelism(1)) {
        while (true) {
            // The pages that have something to say, said during a cut, say it now; and a reader closed ends here.
            yield()
            val ask = asks.tryReceive().getOrNull()
            val picture = if (ask == null) watched.next(window() - Cover, done) else null
            when {
                ask != null -> handle(ask, this)
                picture != null -> cut(picture)
                else -> handle(asks.receive(), this)
            }
        }
    }.also { work -> work.invokeOnCompletion { asks.cancel() } }

    override fun zones(index: Int): StateFlow<PageZones> = zonesOf(index)

    override fun cutting(on: Boolean) {
        asks.trySend(Ask.Cutting(on))
    }

    override fun show(index: Int, pageCount: Int) {
        asks.trySend(Ask.Show(index, pageCount))
    }

    override fun direction(direction: Direction) {
        asks.trySend(Ask.Way(direction))
    }

    override fun close() {
        job.cancel()
    }

    private fun zonesOf(index: Int): MutableStateFlow<PageZones> =
        published.getOrPut(index) { MutableStateFlow(PageZones.Unknown) }

    /**
     * The pages around the page shown, in the order they are fetched ahead: none while the cutting is off. They are
     * the pages looked at and cut, all but the cover.
     */
    private fun window(): List<Int> = shown?.takeIf { on }?.let { windowOrder(it.index, it.pageCount) }.orEmpty()

    private fun handle(ask: Ask, scope: CoroutineScope) {
        when (ask) {
            is Ask.Cutting -> on = ask.on
            is Ask.Show -> shown = ask
            is Ask.Way -> if (ask.direction != direction) {
                direction = ask.direction
                done.clear()
                kept.forget()
                published.values.forEach { it.value = PageZones.Unknown }
            }
            is Ask.Told -> watched.told(ask.index, ask.file)
        }
        // The cover is not cut, and that is said at once, whatever the device has of it.
        val window = window()
        if (Cover in window) zonesOf(Cover).value = PageZones.Whole(WholePage.Cover)
        watched.watch(window - Cover, scope)
    }

    private suspend fun cut(picture: Picture) {
        val bytes = picture.file.length()
        val before = kept.zones(direction, picture.index, bytes)
        zonesOf(picture.index).value = if (before != null) {
            pageZones(before, millis = 0)
        } else {
            val start = TimeSource.Monotonic.markNow()
            val outcome = found(picture.file)
            val millis = start.elapsedNow().inWholeMilliseconds
            // The reader closed during the cut: what was found is neither kept nor shown.
            currentCoroutineContext().ensureActive()
            // What is kept of the page: its zones, none for a file that is no picture, nothing for a cut put off.
            val zones = when (outcome) {
                is CutOutcome.Zones -> outcome.zones
                CutOutcome.NotCut -> emptyList()
                CutOutcome.Later -> null
            }
            if (zones != null) kept.keep(direction, picture.index, bytes, zones)
            when (outcome) {
                is CutOutcome.Zones -> pageZones(outcome.zones, millis)
                CutOutcome.NotCut -> PageZones.Whole(WholePage.NoPicture)
                CutOutcome.Later -> PageZones.Whole(WholePage.Failed)
            }
        }
        done += picture.index
    }

    /**
     * What the cutter makes of [file]; a cut that failed, whatever the reason, the memory left being too short for
     * it among them, is one to do another time: that is not to end the app, and says nothing lasting of the page.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun found(file: File): CutOutcome = try {
        cutter(file, direction)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        CutOutcome.Later
    } catch (_: OutOfMemoryError) {
        CutOutcome.Later
    }
}
