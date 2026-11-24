package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.creditsOf
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.library.model.NextVolume
import java.time.Instant
import kotlin.math.roundToLong

private const val Percent = 100.0

/**
 * What the player shows. Times are in milliseconds; the chapter's are inside the current chapter, the
 * book's on its whole timeline.
 *
 * @property chapterDurationMs null when the server does not know the chapter's length.
 * @property totalMs the book's length; null, with [bookPercent] and [bookTimeLeftMs], when a file's
 * duration is unknown.
 * @property bookTimeLeftMs at the current [speed].
 * @property playing play was asked (the button shows pause), even while waiting for data ([buffering]).
 * @property loading a book is being read; [loadFailed] it could not be.
 * @property offline paused for lack of network, resumes by itself; [unreachable] the server cannot be
 * reached although there is a network, play tries again.
 * @property newerPosition a position from elsewhere, offered ("Go to 1:23:04 (2 h ago)").
 * @property finished the book was listened to its end: the end panel.
 * @property goBackShown a chapter was chosen or a newer position followed: "Go back" returns.
 * @property canRetryLoad a book this app asked for could not be loaded, and can be asked again.
 * @property artworkUrl the cover small, for the mini-player.
 * @property listenedSeconds the time listened to the finished book, from its sessions, for the end
 * panel; null while not known, when there is none, or before the end.
 * @property settings the skip intervals (for the buttons' labels), the last sleep choice, shake to extend.
 * @property narrationFileId the read-along file whose narration (a read-along book's audio) the session holds
 * instead of the book's audiobook; null for an audiobook. See [narrating].
 * @property bookmarks the audiobook's bookmarks in position order, without the ones being deleted; the view model
 * adds them.
 * @property inParts none of the book's chapters has a title of its own: the player calls them parts, as they are
 * named.
 * @property capabilities what the profile's source can do, which the view models add (spec 2c): without audio
 * bookmarks no bookmark, without ratings no stars.
 */
data class PlayerUiState(
    val bookId: BookId? = null,
    val title: String = "",
    val credits: CreditsLine = CreditsLine.None,
    val coverUrl: String? = null,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val chapterElapsedMs: Long = 0,
    val chapterDurationMs: Long? = null,
    val chapterCount: Int = 0,
    val bookTimeMs: Long = 0,
    val totalMs: Long? = null,
    val bookPercent: Double? = null,
    val bookTimeLeftMs: Long? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val sleep: SleepState? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val offline: Boolean = false,
    val unreachable: Boolean = false,
    val newerPosition: OfferedListening? = null,
    val finished: FinishedState? = null,
    val goBackShown: Boolean = false,
    val listenedSeconds: Long? = null,
    val canRetryLoad: Boolean = false,
    val artworkUrl: String? = null,
    val settings: PlayerSettings = PlayerSettings(),
    val narrationFileId: FileId? = null,
    val bookmarks: List<AudioBookmark> = emptyList(),
    val inParts: Boolean = false,
    val capabilities: SourceCapabilities = SourceCapabilities.Everything,
) {
    /**
     * A narration is loaded, not an audiobook: the player screen is not for it (the reader is, through
     * [PlayerController.narration]); there is no end panel ([finished] stays null) nor newer position for it.
     */
    val narrating: Boolean get() = narrationFileId != null
}

/**
 * A row of the chapters sheet; [durationMs] null when unknown. [startMs] is where it starts on the book's
 * timeline, [chapterId] the manifest chapter's id (null for the one chapter of a book without any).
 */
data class ChapterRow(
    val title: String,
    val durationMs: Long?,
    val heard: Boolean,
    val current: Boolean,
    val startMs: Long = 0,
    val chapterId: String? = null,
)

/** A newer position from elsewhere, at [bookTimeMs] on the book's timeline, captured at [updatedAt]. */
data class OfferedListening(val bookTimeMs: Long, val updatedAt: Instant?, val chapter: OfferedChapter? = null)

/** The chapter of an offered place: its [number] from 1, its title, and how far into it the place is. */
data class OfferedChapter(val number: Int, val title: String, val elapsedMs: Long)

/**
 * The end panel's state.
 *
 * @property rating the stars chosen; sent when the panel is closed.
 * @property sending the stars are on their way; [failed] they could not be given.
 * @property panelSeen the panel was dismissed: the player shows the finished book without it, the
 * mini-player offers "Rate".
 * @property device what the device keeps of the book, for the panel's row; null when it keeps nothing whole.
 */
data class FinishedState(
    val rating: Int?,
    val nextVolume: NextVolume?,
    val sending: Boolean,
    val failed: Boolean,
    val panelSeen: Boolean,
    val device: DeviceCopy? = null,
)

/** What the controller adds to the session for the end panel. */
data class EndPanelState(
    val rating: Int? = null,
    val sending: Boolean = false,
    val failed: Boolean = false,
    val panelSeen: Boolean = false,
    val device: DeviceCopy? = null,
)

/**
 * The rows of the chapters sheet, made again only when the chapters or the current one change: the place,
 * which changes twice a second, leaves them as they are. [titleOf] titles a chapter from its position.
 */
internal class ChapterRows(
    private val titleOf: (position: Int, chapter: SessionChapter) -> String = { _, chapter -> chapter.title },
) {
    private var chapters: List<SessionChapter>? = null
    private var index = -1
    private var rows: List<ChapterRow> = emptyList()

    fun of(chapters: List<SessionChapter>, index: Int): List<ChapterRow> {
        if (chapters !== this.chapters || index != this.index) {
            this.chapters = chapters
            this.index = index
            rows = chapters.mapIndexed { i, chapter ->
                ChapterRow(
                    title = titleOf(i, chapter),
                    durationMs = chapter.durationMs,
                    heard = i < index,
                    current = i == index,
                    startMs = chapter.startMs,
                    chapterId = chapter.chapterId,
                )
            }
        }
        return rows
    }
}

/**
 * The player's state from what the session shows, the offer of a newer position, the end panel and the
 * settings. The chapter rows come from [rows], kept while the chapters and the current one stay.
 */
internal fun playerUiState(
    session: SessionSnapshot,
    newer: Offer?,
    end: EndPanelState,
    settings: PlayerSettings,
    rows: ChapterRows = ChapterRows(),
): PlayerUiState {
    val extras = session.extras
    // The first book being read, or that could not be: there is no book yet, but there is something to show.
    val book = extras.book
        ?: return PlayerUiState(loading = extras.loading, loadFailed = extras.loadFailed, settings = settings)
    val chapterIndex = session.chapterIndex.coerceIn(0, (session.chapters.size - 1).coerceAtLeast(0))
    val bookTime = session.bookTimeMs
    val total = book.totalMs.takeIf { book.durationsKnown }
    // A narration is not the audiobook: neither its end panel nor its newer positions.
    val narrating = session.narrating
    return PlayerUiState(
        bookId = book.id,
        title = book.title,
        credits = creditsOf(book.authors, book.narrators),
        coverUrl = book.coverUrl,
        artworkUrl = book.artworkUrl,
        chapterIndex = chapterIndex,
        chapterTitle = session.chapter?.title.orEmpty(),
        chapterElapsedMs = session.chapterElapsedMs,
        chapterDurationMs = session.chapter?.durationMs,
        chapterCount = session.chapters.size,
        bookTimeMs = bookTime,
        totalMs = total,
        bookPercent = total?.takeIf { it > 0 }?.let { bookTime.coerceAtMost(it) * Percent / it },
        bookTimeLeftMs = total?.let { timeLeft(it, bookTime, session.speed) },
        playing = session.playWhenReady && !session.ended,
        buffering = session.buffering,
        speed = session.speed,
        sleep = extras.sleep,
        chapters = rows.of(session.chapters, chapterIndex),
        loading = extras.loading,
        loadFailed = extras.loadFailed,
        offline = extras.offline,
        unreachable = extras.unreachable,
        newerPosition = newer?.takeIf { it.bookId == book.id && !narrating }
            ?.let { OfferedListening(it.bookTimeMs, it.server.capturedAt, chapterAt(session.chapters, it.bookTimeMs)) },
        finished = extras.finished?.takeIf { !narrating }
            ?.let { FinishedState(end.rating, it.nextVolume, end.sending, end.failed, end.panelSeen, end.device) },
        settings = settings,
        narrationFileId = extras.narrationFileId,
        inParts = book.inParts,
    )
}

/**
 * The listening time left from [bookTimeMs] to [totalMs] at [speed]: the book time left divided by the speed (the
 * book time itself at a speed of zero). Every time left the player shows is this, for the book, a chapter or a
 * narration's section; times played, bars and lengths stay in book time.
 */
internal fun timeLeft(totalMs: Long, bookTimeMs: Long, speed: Float): Long {
    val left = (totalMs - bookTimeMs).coerceAtLeast(0)
    return if (speed > 0f) (left / speed.toDouble()).roundToLong() else left
}

/** The chapter that holds [bookTimeMs], with how far into it that is; null without chapters. */
private fun chapterAt(chapters: List<SessionChapter>, bookTimeMs: Long): OfferedChapter? {
    val index = chapters.indexOfLast { it.startMs <= bookTimeMs }.takeIf { it >= 0 } ?: return null
    val chapter = chapters[index]
    return OfferedChapter(index + 1, chapter.title, bookTimeMs - chapter.startMs)
}
