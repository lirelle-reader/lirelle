package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Where a narration is ([cursor]), and how its sections without a title are named ([names]). */
class NarrationSources @Inject constructor(val cursor: NarrationCursor, val names: SectionNames)

/** A narration asked for that could not be loaded, while the session holds another one, or none. */
data class NarrationFailure(val bookId: BookId, val fileId: FileId)

/** The narration of a read-along book in the app's one player: what the reader shows of it and asks of it. */
interface NarrationCommands : NarrationJumpCommands {
    /**
     * The narration the session holds; null unless one is. Its [NarrationUi.loadFailed] is that very narration's
     * load failing again (its "Try again"), never another's nor an audiobook's. With no narration held, the
     * narration asked for that could not be loaded shows as a stub of it ([NarrationUi.loadFailed], nothing else
     * known). Watching it keeps the connection to the session open.
     */
    val narration: StateFlow<NarrationUi?>

    /**
     * The narration asked for that could not be loaded when the session holds another one, or none: what a reader
     * of that book shows (with "Try again", [retryNarration]) while [narration] stays the one held. Null once it
     * loads, once an audiobook is asked, and while nothing failed.
     */
    val narrationFailure: StateFlow<NarrationFailure?>

    /** Each section the narration loaded went past without reading it aloud, while collected. */
    val skipped: SharedFlow<SkippedNotice>

    /** Loads the narration of the read-along file [fileId] of [bookId] from [start]; left paused without [play]. */
    fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean = true)

    fun previousSentence()

    fun nextSentence()

    /** The same command as an audiobook's speed: the service keeps it for the narration. */
    fun narrationSpeed(speed: Float)

    /** Pauses the player, which plays the narration; nothing when it does not play. */
    fun pauseNarration()

    /** Plays the narration loaded; nothing when it plays already (never a toggle). */
    fun playNarration()

    /**
     * "Try again": the narration that could not be loaded, or the one an error stopped, loaded again where it was
     * left ([NarrationStart.Resume]) and played. Nothing when there is neither.
     */
    fun retryNarration()
}

/** A narration asked for, until the session shows it loaded. */
private data class AskedNarration(val bookId: BookId, val fileId: FileId)

/** The narration [this] holds, as it would be asked; null for none. */
private val SessionSnapshot.heldNarration: AskedNarration?
    get() {
        val book = bookId ?: return null
        return extras.narrationFileId?.let { AskedNarration(book, it) }
    }

/** [wanted], when its load failed and [this] holds another narration, or none; else null. */
private fun SessionSnapshot.failedRequest(wanted: AskedNarration?): AskedNarration? =
    wanted?.takeIf { extras.loadFailed && heldNarration != it }

/**
 * The narration as the session ([connection]) and the narration's cursor show it, with the player [settings], its
 * sections without a title named as [sources] names them, and its commands. [onNarrate] is told before a narration
 * is asked for. Call it on the main thread.
 *
 * The narration asked for is kept until the session shows it loaded, or an audiobook is asked ([forget]): a load
 * that fails meanwhile is its failure, shown even when nothing of it was loaded. The sections chosen and "Go back"
 * are [jumps]'.
 */
internal class NarrationControls(
    private val connection: PlayerConnection,
    sources: NarrationSources,
    settings: Flow<PlayerSettings>,
    scope: CoroutineScope,
    private val onNarrate: () -> Unit,
    private val jumps: NarrationJumps = NarrationJumps(connection),
) : NarrationCommands,
    NarrationJumpCommands by jumps {
    private val cursor: NarrationCursor = sources.cursor
    private val names: SectionNames = sources.names
    private val rows = ChapterRows(names::titleOf)
    private val asked = MutableStateFlow<AskedNarration?>(null)

    override val narration: StateFlow<NarrationUi?> = combine(
        connection.snapshot.onEach(::follow),
        cursor.sentence,
        cursor.finished,
        settings,
        asked,
    ) { session, heard, finished, now, wanted ->
        shown(session, heard, finished, wanted)?.copy(settings = now)
    }.combine(jumps.shown) { shown, goBack -> shown?.copy(goBackShown = goBack) }
        .stateIn(scope, SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds), null)

    override val narrationFailure: StateFlow<NarrationFailure?> =
        combine(connection.snapshot, asked) { session, wanted ->
            session.failedRequest(wanted)?.let { NarrationFailure(it.bookId, it.fileId) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(StopWatchingAfter.inWholeMilliseconds), null)

    // The ticker tells the sentence heard before the sections it skipped to reach it.
    override val skipped: SharedFlow<SkippedNotice> = cursor.skipped
        .filter { it.bookId == cursor.sentence.value?.bookId }
        .shareIn(scope, SharingStarted.WhileSubscribed())

    override fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean) {
        onNarrate()
        asked.value = AskedNarration(bookId, fileId)
        val at = start as? NarrationStart.AtSentence
        connection.send(PlayerCommand.LoadNarration(bookId, fileId, at?.sectionIndex, at?.fragment, play))
    }

    override fun previousSentence() = connection.send(PlayerCommand.PreviousSentence)

    override fun nextSentence() = connection.send(PlayerCommand.NextSentence)

    override fun narrationSpeed(speed: Float) = connection.send(PlayerCommand.SetSpeed(speed))

    override fun pauseNarration() = connection.pause()

    override fun playNarration() = connection.play()

    override fun retryNarration() {
        val target = asked.value ?: connection.snapshot.value.heldNarration ?: return
        narrate(target.bookId, target.fileId, NarrationStart.Resume)
    }

    /** An audiobook was asked: a failure from now on is not the narration's. */
    fun forget() {
        asked.value = null
    }

    /** The narration asked for, loaded: it is no longer waited for. The jumps follow the place. */
    private fun follow(session: SessionSnapshot) {
        jumps.onSession(session)
        val wanted = asked.value ?: return
        if (session.heldNarration == wanted && !session.extras.loading && !session.extras.loadFailed) {
            asked.value = null
        }
    }

    /**
     * The narration the session holds, failed only when it is the one asked for again; with none held, the stub
     * of the one asked for that could not be loaded.
     */
    private fun shown(
        session: SessionSnapshot,
        heard: HeardSentence?,
        finished: BookId?,
        wanted: AskedNarration?,
    ): NarrationUi? {
        val held = session.heldNarration
            ?: return session.failedRequest(wanted)?.let { failedNarration(it.bookId, it.fileId) }
        val failed = session.extras.loadFailed && wanted == held
        return narrationUi(session, heard, finished, names, rows)?.copy(loadFailed = failed)
    }
}
