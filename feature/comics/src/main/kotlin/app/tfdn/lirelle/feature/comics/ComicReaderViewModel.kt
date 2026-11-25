package app.tfdn.lirelle.feature.comics

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.comics.ComicPagesOpener
import app.tfdn.lirelle.core.comics.panels.PagePanelsOpener
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionSyncFactory
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import app.tfdn.lirelle.feature.comics.end.EndOfAlbum
import app.tfdn.lirelle.feature.comics.end.EndOfAlbumServices
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** The view model's scope, made before it so that its parts can be given it. */
private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** What opens an album: its [pages], and with them their [panels], the zones they are cut into. */
class ComicOpeners @Inject constructor(
    internal val pages: ComicPagesOpener,
    internal val panels: PagePanelsOpener,
)

/** What a comics reader is made of besides its album, injected together. */
class ComicReaderServices @Inject constructor(
    internal val books: ComicBooks,
    internal val end: EndOfAlbumServices,
    internal val openers: ComicOpeners,
    internal val positions: PositionSyncFactory,
    internal val clocks: SessionClockFactory,
    internal val outbox: Outbox,
    internal val flusher: OutboxFlusher,
    internal val settings: ComicSettingsStore,
    internal val timings: ComicTimings,
)

/**
 * The parts of the comics reader, wired together. [scope] is the view model's; [lasting] outlives it.
 * With [fromStart] the album opens at its first page, not at its position. [online] tells whether the
 * device has a network.
 */
internal class ComicReader(
    bookId: BookId,
    fromStart: Boolean,
    val scope: CoroutineScope,
    services: ComicReaderServices,
    online: ComicNetwork,
    lasting: CoroutineScope,
) {
    val core = ComicCore(bookId, scope)
    val bars = Bars(core, services.timings)
    val settings = SettingsPart(core, services.settings, bars)
    val end = EndOfAlbum(core, services.end)
    val turning = Turning(core, services.timings, bars, end)
    val scrolling = Scrolling(core, turning, bars, end)
    val opening = ComicOpening(core, services, lasting, fromStart, OpenedParts(settings, turning, bars))
    private val network = NetworkReturn(core, online, opening)
    val host = ComicHost(core, turning, scrolling, network)
    val panelMode = PanelMode(core, settings, bars, services.settings)

    init {
        val watch = PageWatch(core)
        // The pages shown are watched before the panels are told of them: the page shown is then cut first.
        core.onLayout = {
            watch.refresh()
            panelMode.refresh()
        }
        network.watch()
        opening.start()
    }

    /** What was read is written; the album's pages stay, for a reader that is not gone yet. */
    fun leave() {
        // A strip left while it moves told no rest: the page at its top is written first.
        scrolling.stripRested()
        core.session?.close()
        core.events.trySend(ComicReaderEvent.Leave)
    }

    /** The reader is gone: what was read is written if it was not, and the pages are given back. */
    fun close() {
        core.session?.close()
        core.panels?.close()
        core.pages?.close()
    }
}

/**
 * The reader of one comic: it fetches the book, opens its album at the user's position, or at its first
 * page for an album read again, lays its pages out by one or by two, keeps the position and the reading
 * session in step with the server, and holds what the page view, the bars, the settings sheet and the panel
 * at the end of the album show.
 * What the user does is in [ComicReaderActions]; what the screen and its window report is in
 * [ComicHostEvents].
 */
@HiltViewModel(assistedFactory = ComicReaderViewModel.Factory::class)
class ComicReaderViewModel private constructor(private val reader: ComicReader) :
    ViewModel(reader.scope),
    ComicReaderActions,
    ComicNavigation by reader.turning,
    StripNavigation by reader.scrolling,
    ComicSettingsActions by reader.settings,
    NewerPageActions by reader.host,
    ComicEndActions by reader.end,
    PanelModeActions by reader.panelMode,
    ComicHostEvents by reader.host {

    @AssistedInject
    constructor(
        @Assisted bookId: String,
        @Assisted fromStart: Boolean,
        services: ComicReaderServices,
        network: ComicNetwork,
        @ComicsLeaveScope lasting: CoroutineScope,
    ) : this(ComicReader(BookId(bookId), fromStart, mainScope(), services, network, lasting))

    @AssistedFactory
    interface Factory {
        /**
         * [bookId] is the book's id as text: an assisted factory takes no id of the app's own. With [fromStart]
         * the album is read again: it opens at its first page, not at its position.
         */
        fun create(bookId: String, fromStart: Boolean): ComicReaderViewModel
    }

    val state: StateFlow<ComicReaderUiState> = reader.core.state.asStateFlow()

    /** One collector: each event is delivered once, and kept until it is collected. */
    val events: Flow<ComicReaderEvent> = reader.core.events.receiveAsFlow()

    override fun toggleBars() = reader.bars.toggle()

    override fun hideBars() = reader.bars.hide()

    override fun retryOpening() = reader.opening.start()

    override fun leave() = reader.leave()

    override fun onCleared() = reader.close()
}
