package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.audio.bookmarks.AddOutcome
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioBookmark
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.Shortened
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerUiState

/** What the player screen asked for, each as a short line. */
class RecordingActions : PlayerActions {
    val asked = mutableListOf<String>()

    override fun playPause() {
        asked += "playPause"
    }

    override fun back() {
        asked += "back"
    }

    override fun forward() {
        asked += "forward"
    }

    override fun previousChapter() {
        asked += "previousChapter"
    }

    override fun nextChapter() {
        asked += "nextChapter"
    }

    /** The fractions sought in the chapter, in order. */
    val sought = mutableListOf<Float>()

    override fun seekInChapter(fraction: Float) {
        asked += "seekInChapter"
        sought += fraction
    }

    override fun setSpeed(speed: Float) {
        asked += "setSpeed $speed"
    }

    override fun startSleep(choice: SleepChoice) {
        asked += "startSleep $choice"
    }

    override fun cancelSleep() {
        asked += "cancelSleep"
    }

    override fun stopListening() {
        asked += "stopListening"
    }

    override fun goToChapter(index: Int) {
        asked += "goToChapter $index"
    }

    override fun goBack() {
        asked += "goBack"
    }

    override fun acceptNewerPosition() {
        asked += "acceptNewerPosition"
    }

    override fun ignoreNewerPosition() {
        asked += "ignoreNewerPosition"
    }

    override fun setRating(stars: Int?) {
        asked += "setRating $stars"
    }

    override fun closeEndPanel() {
        asked += "closeEndPanel"
    }

    override fun dismissEndPanel() {
        asked += "dismissEndPanel"
    }

    override fun openEndPanel() {
        asked += "openEndPanel"
    }

    override fun listenToNext() {
        asked += "listenToNext"
    }

    override fun deleteFromDevice() {
        asked += "deleteFromDevice"
    }

    override fun keepOnDevice() {
        asked += "keepOnDevice"
    }

    override fun setShakeToExtend(enabled: Boolean) {
        asked += "setShakeToExtend $enabled"
    }

    override fun retryLoad() {
        asked += "retryLoad"
    }

    /** What an add answers. */
    var addAnswer: AddOutcome? = null

    override suspend fun addBookmark(): AddOutcome? {
        asked += "addBookmark"
        return addAnswer
    }

    override fun editBookmark(bookmark: AudioBookmark, title: String, note: String?) {
        asked += "editBookmark ${bookmark.id} $title $note"
    }

    override fun goToBookmark(bookmark: AudioBookmark) {
        asked += "goToBookmark ${bookmark.id}"
    }

    override fun deleteBookmark(bookmark: AudioBookmark) {
        asked += "deleteBookmark ${bookmark.id}"
    }

    override fun undoDelete(bookmark: AudioBookmark) {
        asked += "undoDelete ${bookmark.id}"
    }

    override fun confirmDelete(bookmark: AudioBookmark) {
        asked += "confirmDelete ${bookmark.id}"
    }

    override fun refreshBookmarks() {
        asked += "refreshBookmarks"
    }
}

/** Invented books for the player's screen tests. */
object PlayerSamples {
    /** 0:10 into the second of three chapters (60 s, 40 s, 60 s), playing. */
    val playing = PlayerUiState(
        bookId = BookId("42"),
        title = "The Long Road",
        credits = CreditsLine.AuthorsAndNarrators(
            Shortened("Anne Verlaine", null, 0),
            Shortened("Thomas Rivière", null, 0),
        ),
        chapterIndex = 1,
        chapterTitle = "Middle",
        chapterElapsedMs = 10_000,
        chapterDurationMs = 40_000,
        chapterCount = 3,
        bookTimeMs = 70_000,
        totalMs = 160_000,
        bookPercent = 43.75,
        bookTimeLeftMs = 90_000,
        playing = true,
        chapters = listOf(
            ChapterRow("Opening", 60_000, heard = true, current = false, startMs = 0, chapterId = "c1"),
            ChapterRow("Middle", 40_000, heard = false, current = true, startMs = 60_000, chapterId = "c2"),
            ChapterRow("Closing", 60_000, heard = false, current = false, startMs = 100_000, chapterId = "c3"),
        ),
        settings = PlayerSettings(),
    )

    /** The narration of the same book, 4 s into its second narrated section (the book's fourth, untitled). */
    val narration = NarrationUi(
        bookId = BookId("42"),
        fileId = FileId("1"),
        title = "The Long Road",
        sectionIndex = 3,
        sectionTitle = "Section 4",
        sectionNumber = 2,
        sectionCount = 2,
        elapsedMs = 4_000,
        sectionDurationMs = 10_000,
        bookPercent = 76.0,
        timeLeftMs = 6_000,
        playing = true,
        speed = 1f,
        sleep = null,
        sections = listOf(
            ChapterRow("Opening", 15_000, heard = true, current = false),
            ChapterRow("Section 4", 10_000, heard = false, current = true),
        ),
        sentence = null,
        finished = false,
    )

    /** The player's state while [narration] is loaded. */
    val narrating = playing.copy(narrationFileId = FileId("1"), chapterTitle = "", bookPercent = 76.0)
}
