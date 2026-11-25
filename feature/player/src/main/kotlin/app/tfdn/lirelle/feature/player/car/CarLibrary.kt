package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.network.ApiResult
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What a car browses of one profile: the rows of its Home, what the device keeps of it, its books to listen to
 * and their series — only what the player can play ([listenable]). It knows nothing of the car's own items:
 * the car's browser turns its answers into them.
 *
 * @param clock the clock that does not jump: the audio library is kept a short time by it.
 * @param signInNeeded whether the profile waits for a sign-in: its server then refuses everything, and only
 *   what the device keeps is listed.
 */
class CarLibrary(
    private val library: LibraryRepository,
    private val capabilities: SourceCapabilities,
    private val downloads: Downloads,
    private val kept: KeptBookData,
    clock: MonotonicClock,
    private val signInNeeded: () -> Boolean,
) : CarShelves {
    /** For the playback service, from its profile's objects. */
    @Inject
    constructor(
        library: LibraryRepository,
        capabilities: SourceCapabilities,
        downloads: Downloads,
        kept: KeptBookData,
        clock: MonotonicClock,
        network: ProfileNetwork,
    ) : this(library, capabilities, downloads, kept, clock, { network.waitsForSignIn.value })

    private val books = ListenableBooks(library, capabilities, clock)

    /**
     * The books whose audiobook or narration the device keeps whole, as they change: what the Downloaded tab
     * is made of.
     */
    val keptAudio: Flow<Set<BookId>> = downloads.books.map { books ->
        books.filter { book -> book.formats.any { it.isKeptAudio } }.mapTo(mutableSetOf()) { it.bookId }
    }

    /**
     * What [place] lists, [pageSize] at a time, pages numbered from 0. The root always lists its tabs; Home is
     * one page, its sections whole.
     */
    override suspend fun children(place: CarPlace, page: Int, pageSize: Int): CarAnswer {
        if (place != CarPlace.Root) {
            refusal(asksServer = place != CarPlace.Tab.Downloaded)?.let { return CarAnswer.Notice(it) }
        }
        val onePage = place == CarPlace.Tab.Home
        if (onePage && page > 0) return CarAnswer.Nodes(emptyList())
        val whole = listed(place)
        return (if (onePage) whole else whole?.pageOf(page, pageSize)).answered()
    }

    /** Everything [place] lists; null when its server could not be had. */
    private suspend fun listed(place: CarPlace): List<CarNode>? = when (place) {
        CarPlace.Root -> CarPlace.Tab.entries.map(CarNode::Tab)
        CarPlace.Tab.Home -> home()
        CarPlace.Tab.Downloaded -> downloaded()
        CarPlace.Tab.Audiobooks -> books.all()?.map { it.carBook(authorLine(it.authors)) }
        CarPlace.Tab.Series -> books.series()
        is CarPlace.OneSeries -> books.ofSeries(place.id)?.map { it.carBook(it.seriesLine()) }
    }

    /** The books to listen to that the server finds for [text]: its first ones. Nothing for a blank text. */
    override suspend fun search(text: String): CarAnswer {
        if (text.isBlank()) return CarAnswer.Nodes(emptyList())
        refusal(asksServer = true)?.let { return CarAnswer.Notice(it) }
        return books.matching(text.trim())?.map { it.carBook(authorLine(it.authors)) }.answered()
    }

    /** A list the server could not give is said as one thing: the car has one way to say it. */
    private fun List<CarNode>?.answered(): CarAnswer =
        this?.let(CarAnswer::Nodes) ?: CarAnswer.Notice(CarNotice.Unreachable)

    /** Why nothing is listed, known before anything is asked; null when the place can be listed. */
    private fun refusal(asksServer: Boolean): CarNotice? = when {
        !capabilities.hasListening -> CarNotice.NothingToListen
        asksServer && signInNeeded() -> CarNotice.SignInNeeded
        else -> null
    }

    /**
     * The rows of Home the source has, asked together and as long as their "See all" screens: a row's first
     * books may all be texts. A row that fails is left out; null when all of them fail. A book of several rows
     * shows in the first.
     */
    private suspend fun home(): List<CarNode>? = coroutineScope {
        val asked = CarSection.entries.filter { it.row in capabilities.homeRows }
        val rows = asked.map { section -> async { section to library.homeRow(section.row, HomeRowExtent.SeeAll) } }
            .awaitAll()
            .mapNotNull { (section, row) -> (row as? ApiResult.Success)?.let { section to it.value.books } }
        if (rows.isEmpty() && asked.isNotEmpty()) return@coroutineScope null
        rows.flatMap { (section, books) ->
            books.filter { listenable(it, capabilities) }.map { it.carBook(it.lineIn(section), section) }
        }.distinctBy { it.id }
    }

    /**
     * The books whose audiobook or narration the device keeps whole, the last opened first, named by the page
     * kept with them: the server is asked nothing. A book without its page has no title to show, and is left
     * out.
     */
    private suspend fun downloaded(): List<CarNode> = downloads.books.first()
        .filter { book -> book.formats.any { it.isKeptAudio } }
        .sortedByDescending { it.lastActivity }
        .mapNotNull { book ->
            kept.detail(book.bookId)?.let { page ->
                CarNode.Book(book.bookId, page.title, authorLine(page.authors.map { it.name }))
            }
        }
}

/** An audiobook or a narration that is whole on the device, and not being deleted. */
private val FormatRecord.isKeptAudio: Boolean
    get() = format != DownloadFormat.Text && !removing && state is FormatState.Complete
