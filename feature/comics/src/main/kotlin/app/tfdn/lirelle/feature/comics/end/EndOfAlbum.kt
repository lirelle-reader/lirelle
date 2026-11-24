package app.tfdn.lirelle.feature.comics.end

import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.downloads.DeviceCopyControl
import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.model.VolumeGroup
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.comics.ComicCore
import app.tfdn.lirelle.feature.comics.ComicEndActions
import app.tfdn.lirelle.feature.comics.ComicEndPanel
import app.tfdn.lirelle.feature.comics.ComicReaderEvent
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val Stars = 1..5

/**
 * What the end of an album asks the server, whom it tells that a rating reached it, what says what the
 * device keeps of the book, and what the profile's source can do; injected together.
 */
class EndOfAlbumServices @Inject constructor(
    internal val repository: ReaderRepository,
    internal val changes: ReadingChanges,
    internal val device: DeviceCopySources,
    internal val capabilities: SourceCapabilities,
)

/**
 * The panel at the end of the album, offered at each turn past the last page. The server marks the album as read
 * by itself, from the position at its end; a rating chosen in the panel is sent by "Close the album" or "Read
 * the next one", as [AlbumRating] describes, and the reader is left once it is. Without stars, or on the stars
 * already sent, nothing is sent and the reader is left at once. Dismissed, the panel sends nothing and the
 * reader stays on the last page; it comes back with the stars it was left with.
 *
 * A book the device keeps, whatever its pages are (a PDF, a comic, a DjVu), has a row in the panel to delete it
 * from there ([device]): the book is open, so it goes once the reader is left, and the row then says so and
 * offers to undo it. An album only part of which is fetched is not kept, and has no row.
 *
 * A source without ratings has a panel without stars, which takes none and sends none (spec 2c).
 */
internal class EndOfAlbum(
    private val core: ComicCore,
    services: EndOfAlbumServices,
    private val device: DeviceCopyControl = DeviceCopyControl(services.device, core.scope),
) : ComicEndActions, DeviceCopyActions by device {
    private val repository = services.repository
    private val rating = AlbumRating(core, repository, services.changes)
    private val ratings = services.capabilities.ratings

    init {
        device.show(core.bookId)
        // The row follows what the device keeps, while the panel is shown.
        core.scope.launch {
            device.copy.collect { copy -> core.update { it.copy(endPanel = it.endPanel?.copy(device = copy)) } }
        }
    }

    /** The stars the panel was dismissed with, shown again when it comes back. */
    private var chosen: Int? = null

    /** The stars the server holds from this opening, null before any were sent. */
    private var sent: Int? = null

    /**
     * The next album: null until the server has said, and when there is none. What the server said is asked
     * once; while it did not say, each opening of the panel asks again, one question at a time.
     */
    private var nextVolume: NextVolume? = null
    private var nextKnown = false
    private var askingNext: Job? = null

    /** What the user last asked the panel for, and the rating sent before it is done. */
    private var wanted: ComicReaderEvent? = null
    private var finishing: Job? = null

    /** The reader shows a place of the album, [fraction] of the way through it; followed for a rating only. */
    fun onPosition(fraction: Double) {
        if (ratings) rating.onPosition(fraction)
    }

    /**
     * The user turned past the last page: the panel opens, and the position at the end, written when the last
     * page was reached, is sent without waiting any longer. Arriving at the last page opens nothing.
     */
    fun onPastEnd() {
        if (core.current.endPanel != null) return
        val panel =
            ComicEndPanel(rating = chosen, nextVolume = nextVolume, device = device.copy.value, ratings = ratings)
        core.update { it.copy(endPanel = panel, barsShown = false) }
        core.scope.launch { core.session?.flush() }
        val seriesId = core.seriesId ?: return
        if (nextKnown || askingNext?.isActive == true) return
        askingNext = core.scope.launch {
            val answer = repository.nextVolume(seriesId, core.bookId, VolumeGroup.Comics)
            val next = (answer as? ApiResult.Success ?: return@launch).value
            nextKnown = true
            nextVolume = next
            core.update { it.copy(endPanel = it.endPanel?.copy(nextVolume = next)) }
        }
    }

    override fun setRating(stars: Int?) {
        if (!ratings) return
        core.update { it.copy(endPanel = it.endPanel?.copy(rating = stars?.coerceIn(Stars), failed = false)) }
    }

    override fun readNext() {
        val next = core.current.endPanel?.nextVolume ?: return
        finish(then = ComicReaderEvent.OpenNext(next.bookId))
    }

    override fun closeAlbum() = finish(then = ComicReaderEvent.Leave)

    override fun retryRating() {
        val again = wanted?.takeIf { core.current.endPanel?.failed == true } ?: return
        finish(again)
    }

    /** A rating on its way is given up: nothing is left that would leave the reader later. */
    override fun dismissEndPanel() {
        val panel = core.current.endPanel ?: return
        finishing?.cancel()
        chosen = panel.rating
        core.update { it.copy(endPanel = null) }
    }

    /**
     * The rating is sent if there is one to send; then what was read is written, as when the reader is left by
     * back, and [then] is sent. Not while a rating is on its way; when it could not be sent, the panel stays
     * and says so.
     */
    private fun finish(then: ComicReaderEvent) {
        val panel = core.current.endPanel?.takeUnless { it.sending } ?: return
        wanted = then
        finishing = core.scope.launch {
            val stars = panel.rating
            if (stars != null && stars != sent) {
                core.update { it.copy(endPanel = it.endPanel?.copy(sending = true, failed = false)) }
                if (!rating.rate(stars)) {
                    core.update { it.copy(endPanel = it.endPanel?.copy(sending = false, failed = true)) }
                    return@launch
                }
                sent = stars
            }
            core.update { it.copy(endPanel = null) }
            core.session?.close()
            core.events.trySend(then)
        }
    }
}
