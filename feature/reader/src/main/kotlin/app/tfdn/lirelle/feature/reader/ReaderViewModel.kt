package app.tfdn.lirelle.feature.reader

import androidx.lifecycle.ViewModel
import app.tfdn.lirelle.core.downloads.DeviceCopyControl
import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.marks.Marks
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.narration.Following
import app.tfdn.lirelle.feature.reader.narration.NarrationPart
import app.tfdn.lirelle.feature.reader.narration.NarrationServices
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationActions
import app.tfdn.lirelle.feature.reader.text.TextActions
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

/** The names of the reader's two assisted flags, which share a type. */
private const val FromStart = "fromStart"
private const val Narrate = "narrate"

/** The view model's scope, made before it so that its parts can be given it. */
private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** What a reader is made of besides its book, injected together. */
class ReaderServices @Inject constructor(
    internal val opener: BookOpener,
    internal val sessions: ReadingSessionFactory,
    internal val repository: ReaderRepository,
    internal val settings: ReaderSettingsStore,
    internal val changes: ReadingChanges,
    internal val marks: Marks,
    internal val textActions: TextActions,
    internal val narration: NarrationServices,
    internal val device: DeviceCopySources,
)

/**
 * The parts of the reader, wired together. [scope] is the view model's; [lasting] outlives it. [capabilities]
 * are what the profile's source can do (spec 2c).
 * With [fromStart] the book opens at its beginning, not at its position; with [narrate] its narration
 * starts from the page shown, unless it is already loaded.
 */
internal class Reader(
    bookId: BookId,
    fromStart: Boolean,
    narrate: Boolean,
    val scope: CoroutineScope,
    services: ReaderServices,
    lasting: CoroutineScope,
    capabilities: SourceCapabilities,
) {
    val core = ReaderCore(bookId, scope, capabilities)
    private val appearance = Appearance(core, services.settings, lasting)
    private val opening = Opening(core, services.opener, services.sessions, appearance.loaded, fromStart)
    private val device = DeviceCopyControl(services.device, scope).apply { show(bookId) }
    val endOfBook = EndOfBook(core, services.repository, services.changes, device)
    val navigation = Navigation(core)

    val search = SearchPart(core)
    private val notes = NotesPart(core)
    val marks = MarksPart(core, services.marks, services.textActions, appearance, lasting, search::searchFor)
    val chrome = Chrome(core, appearance, opening, marks)
    val prompts = Prompts(core)
    val host = HostEvents(core, appearance, opening, endOfBook, navigation, marks, search, notes)
    // A source without read-along narrates nothing, even asked to by an old link (spec 2c).
    val narration = NarrationPart(
        core,
        services.narration,
        endOfBook,
        { marks.selection.onBack() },
        narrate && capabilities.readAlong,
    )

    init {
        core.driver.listener = PageEvents(core, appearance, opening, endOfBook, marks, search, notes, narration)
        core.onNavigated = {
            narration.following.navigated()
            narration.starts.forget()
        }
        // While away the page may have missed a move to follow (a touch whose end it was never told held it): it is
        // told the sentence again, to go there at once.
        core.onFront = {
            if (core.current.narration.following == Following.On) narration.following.follow()
        }
        appearance.start()
        marks.start()
        narration.start()
        opening.start()
    }

    /** A brightness a finger was still moving is stored, then the reading is closed. */
    fun leave() {
        appearance.keepBrightness()
        host.onLeave()
    }
}

/**
 * The reader of one book: it fetches the book, opens it in the page at the user's position, or at
 * its beginning for a book read again, keeps the position and the reading session in step with
 * the server, and holds what the bars, the sheets and the panels show, the book's marks included. It
 * follows the narration of its book when the player reads it aloud (spec 7).
 * What the user does is in [ReaderActions]; what the screen and its window report is in
 * [ReaderHostEvents].
 */
@HiltViewModel(assistedFactory = ReaderViewModel.Factory::class)
class ReaderViewModel private constructor(private val reader: Reader) :
    ViewModel(reader.scope),
    ReaderActions,
    ReaderNavigation by reader.navigation,
    ReaderChrome by reader.chrome,
    ReaderPrompts by reader.prompts,
    EndPanelActions by reader.endOfBook,
    SelectionActions by reader.marks.selection,
    HighlightPanelActions by reader.marks.panel,
    MarkListActions by reader.marks.bookmarks,
    SearchActions by reader.search,
    ReaderNarrationActions by reader.narration,
    ReaderHostEvents by reader.host {

    @AssistedInject
    constructor(
        @Assisted bookId: String,
        @Assisted(FromStart) fromStart: Boolean,
        @Assisted(Narrate) narrate: Boolean,
        services: ReaderServices,
        @ReaderLeaveScope lasting: CoroutineScope,
        capabilities: SourceCapabilities,
    ) : this(Reader(BookId(bookId), fromStart, narrate, mainScope(), services, lasting, capabilities))

    @AssistedFactory
    interface Factory {
        /**
         * [bookId] is the book's id as text: an assisted factory takes no id of the app's own. With [fromStart]
         * the book is read again: it opens at its beginning, not at its position. With [narrate] its narration
         * starts from the page the book opens at, unless it is loaded already.
         */
        fun create(
            bookId: String,
            @Assisted(FromStart) fromStart: Boolean,
            @Assisted(Narrate) narrate: Boolean,
        ): ReaderViewModel
    }

    val state: StateFlow<ReaderUiState> = reader.core.state.asStateFlow()

    /** What the narration's bar and panel ask of the player, through the reader. */
    val narrationControls: NarrationActions = reader.narration.panelActions

    /** One collector: each event is delivered once, and kept until it is collected. */
    val events: Flow<ReaderEvent> = reader.core.events.receiveAsFlow()

    override fun onLeave() = reader.leave()

    override fun onNoteLabels(labels: PageNoteLabels) {
        if (labels == reader.core.noteLabels) return
        reader.core.noteLabels = labels
        reader.core.pushSettings()
    }

    override fun onMarkLabels(labels: MarkLabels) {
        reader.core.markLabels = labels
    }

    override fun onCleared() = onLeave()
}
