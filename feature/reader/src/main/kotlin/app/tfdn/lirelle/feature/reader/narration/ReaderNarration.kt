package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.player.service.NarrationStart
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Whether the page goes along with the sentence heard. */
enum class Following {
    On,

    /** The user navigated: the strip shows. */
    Off,
}

/** What the strip above the narration bar holds. */
enum class StripUi {
    /** Playing, following stopped: "Back to the sentence". */
    BackOnly,

    /** Paused, the sentence off the page: "Back to the sentence" and "Read this page". */
    BackAndReadPage,
}

/**
 * The reader during a narration of its book.
 *
 * @property available the file read has a narration: the wave button shows.
 * @property active this book's narration, loaded in the player (playing or paused); null otherwise.
 * @property sentenceOnPage the sentence heard is on the page shown, as the page last said.
 * @property strip what the strip above the narration bar holds; null when it is not shown.
 * @property notice a section the narration went past without reading it, to tell; null when none.
 * @property ended the narration was listened to its end: "Finish the book".
 * @property unavailable the narration's map could not be had: "Narration unavailable · Try again".
 * @property loadFailed this book's narration was asked and could not be loaded while none of it is held (another
 *   book's narration may be): its failure is told, with "Try again".
 */
data class ReaderNarrationUi(
    val available: Boolean = false,
    val active: NarrationUi? = null,
    val following: Following = Following.On,
    val sentenceOnPage: Boolean = true,
    val strip: StripUi? = null,
    val notice: SkippedNotice? = null,
    val ended: Boolean = false,
    val unavailable: Boolean = false,
    val loadFailed: Boolean = false,
)

/** "Stop listening" from the reader: the narration's bar swiped away while it does not play, and "Undo". */
interface ReaderNarrationStop {
    /** The narration, paused or ended, is closed, its place saved first; nothing while it plays. */
    fun stopNarration()

    /** "Undo": the narration stopped last is loaded again where it was left, paused. */
    fun undoStopNarration()
}

/** What the user can do with the narration from the reader. Nothing happens while the book is not on screen. */
interface ReaderNarrationActions : ReaderNarrationStop {
    /** The wave button: from the first narrated sentence of the page shown, or the next one after it. */
    fun startHere()

    /** "Read from here": from the narrated sentence holding the start of the selection, or the next one. */
    fun readFromSelection()

    /** "Read this page", in the strip: as [startHere]. */
    fun readThisPage()

    /** "Back to the sentence": the page goes to the sentence heard and follows it again. */
    fun backToSentence()

    /** "Finish the book", once the narration ended: the panel at the end of the book. */
    fun finishBook()

    /** "Read" on a skipped section's notice: the narration pauses and the page opens that section. */
    fun readSkipped(notice: SkippedNotice)

    /** The notice was shown long enough, or swiped away. */
    fun dismissNotice(notice: SkippedNotice)

    /** "Try again" after the narration was unavailable: what was asked is asked again. */
    fun retryNarration()
}

/** The app's player, as far as the reader follows a narration and starts one. */
interface ReaderNarrationPlayer {
    /** The narration loaded; null when none is. */
    val narration: Flow<NarrationUi?>

    /** The sentence heard, published by the player's service; null when no narration is loaded. */
    val heard: StateFlow<HeardSentence?>

    /** Each section the narration went past without reading it aloud. */
    val skipped: Flow<SkippedNotice>

    /** The narration last asked for that could not be loaded while another one, or none, is held; null when none. */
    val failure: Flow<NarrationFailure?>

    /** Loads the narration of [fileId] of [bookId] from [start], and plays it unless [play] is false. */
    fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean = true)

    /** Pauses the player; nothing when it does not play. */
    fun pause()

    /** Plays the narration loaded, paused; nothing when it plays already. */
    fun play()

    /** The narration that could not be loaded, or that an error stopped, loaded again where it was, and played. */
    fun retryNarration()

    /** What the narration's bar, panel and sheets ask of the player. */
    val actions: NarrationActions
}

/** Whether anything of the narration is known: one that could not be loaded is shown without sections. */
internal val NarrationUi.loaded: Boolean get() = sectionCount > 0

/** What the reader needs for a narration, injected together. */
class NarrationServices @Inject constructor(
    internal val player: ReaderNarrationPlayer,
    internal val maps: ReadAlongMapStore,
    internal val places: NarrationStore,
    internal val kept: KeptBookData,
    internal val downloads: Downloads,
)
