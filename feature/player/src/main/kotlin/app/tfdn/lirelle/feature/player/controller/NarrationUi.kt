package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.feature.player.service.sectionTitle

private const val Percent = 100.0

/** A narration that could not be loaded has no title known, nor any section: the reader shows the book's own. */
private const val NothingKnown = ""

/**
 * The narration loaded in the player (the audio of a read-along book), as the reader shows it. Its sections are
 * the session's chapters: only the narrated ones. Times are in milliseconds.
 *
 * @property sectionIndex the current section's place in the book's reading order (the spine), as
 *   [HeardSentence.sectionIndex] gives it.
 * @property sectionTitle the section's own title, or "Section N" by its place in the book when it has none.
 * @property sectionNumber the section's place among the narrated ones, from 1, of [sectionCount].
 * @property elapsedMs how far into the section playback is, of [sectionDurationMs].
 * @property bookPercent how far into the narration, from 0 to 100.
 * @property timeLeftMs the narration's time left, at the current [speed].
 * @property sections the rows of the sections sheet, titled as [sectionTitle] is.
 * @property sentence the sentence heard, from the narration's cursor; null while none is known.
 * @property finished the narration was listened to its end.
 * @property settings the player settings: the skip intervals, the last sleep choice, shake to extend.
 * @property loadFailed this narration, asked for again, could not be loaded ("Try again":
 *   [NarrationCommands.retryNarration]); its details are empty when nothing of it is held (another narration's
 *   failure, with one held, is [NarrationCommands.narrationFailure]).
 * @property offline paused for lack of network, resumes by itself, as the 3a player says.
 * @property unreachable the server cannot be reached although there is a network: play tries again.
 * @property error an error stopped playback, not the network's ([offline], [unreachable]): "Try again".
 * @property goBackShown a section was chosen ([NarrationJumpCommands.goToSection]): "Go back" returns from it.
 */
data class NarrationUi(
    val bookId: BookId,
    val fileId: FileId,
    val title: String,
    val sectionIndex: Int,
    val sectionTitle: String,
    val sectionNumber: Int,
    val sectionCount: Int,
    val elapsedMs: Long,
    val sectionDurationMs: Long,
    val bookPercent: Double,
    val timeLeftMs: Long,
    val playing: Boolean,
    val speed: Float,
    val sleep: SleepState?,
    val sections: List<ChapterRow>,
    val sentence: HeardSentence?,
    val finished: Boolean,
    val settings: PlayerSettings = PlayerSettings(),
    val loadFailed: Boolean = false,
    val offline: Boolean = false,
    val unreachable: Boolean = false,
    val error: Boolean = false,
    val goBackShown: Boolean = false,
)

/** How a section without a title is named: "Section 12", by its [number] in the book's reading order, from 1. */
fun interface SectionNames {
    fun untitled(number: Int): String
}

/** A narration's chapter's title, by the service's rule ([sectionTitle]), named as these names name them. */
internal fun SectionNames.titleOf(position: Int, chapter: SessionChapter): String =
    sectionTitle(chapter.title, chapter.sectionIndex, position, ::untitled)

/**
 * The narration [session] shows, with the sentence the cursor [heard] and the book whose narration the cursor saw
 * end ([finishedBook]); null when the session holds no narration. The rows come from [rows], kept while the
 * sections and the current one stay. Its [NarrationUi.settings] are the defaults: the caller adds the player's,
 * and whether it failed ([NarrationUi.loadFailed]).
 */
internal fun narrationUi(
    session: SessionSnapshot,
    heard: HeardSentence?,
    finishedBook: BookId?,
    names: SectionNames,
    rows: ChapterRows,
): NarrationUi? {
    val fileId = session.extras.narrationFileId ?: return null
    val book = session.extras.book ?: return null
    val index = session.chapterIndex.coerceIn(0, (session.chapters.size - 1).coerceAtLeast(0))
    val chapter = session.chapters.getOrNull(index)
    val bookTime = session.bookTimeMs
    val total = book.totalMs
    return NarrationUi(
        bookId = book.id,
        fileId = fileId,
        title = book.title,
        sectionIndex = chapter?.sectionIndex ?: index,
        sectionTitle = chapter?.let { names.titleOf(index, it) }.orEmpty(),
        sectionNumber = if (chapter == null) 0 else index + 1,
        sectionCount = session.chapters.size,
        elapsedMs = session.chapterElapsedMs,
        sectionDurationMs = chapter?.durationMs ?: 0,
        bookPercent = if (total > 0) bookTime.coerceAtMost(total) * Percent / total else 0.0,
        timeLeftMs = timeLeft(total, bookTime, session.speed),
        playing = session.playWhenReady && !session.ended,
        speed = session.speed,
        sleep = session.extras.sleep,
        sections = rows.of(session.chapters, index),
        sentence = heard?.takeIf { it.bookId == book.id },
        finished = session.extras.narrationFinished || finishedBook == book.id,
        offline = session.extras.offline,
        unreachable = session.extras.unreachable,
        error = session.error && !session.extras.offline && !session.extras.unreachable,
    )
}

/** The narration of [fileId] of [bookId] that could not be loaded, with nothing of it known. */
internal fun failedNarration(bookId: BookId, fileId: FileId): NarrationUi = NarrationUi(
    bookId = bookId,
    fileId = fileId,
    title = NothingKnown,
    sectionIndex = 0,
    sectionTitle = NothingKnown,
    sectionNumber = 0,
    sectionCount = 0,
    elapsedMs = 0,
    sectionDurationMs = 0,
    bookPercent = 0.0,
    timeLeftMs = 0,
    playing = false,
    speed = 1f,
    sleep = null,
    sections = emptyList(),
    sentence = null,
    finished = false,
    loadFailed = true,
)
