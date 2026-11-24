package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timeline.CreditsLine
import app.tfdn.lirelle.core.audio.timeline.Shortened
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.feature.player.service.FinishedBook
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class PlayerUiStateTest {

    private fun map(
        snapshot: SessionSnapshot,
        newer: Offer? = null,
        end: EndPanelState = EndPanelState(),
        settings: PlayerSettings = PlayerSettings(),
    ) = playerUiState(snapshot, newer, end, settings)

    @Test
    fun `the chapter rows stay the same while the place moves in the chapter, and change with it`() {
        val rows = ChapterRows()
        val at = SessionBooks.at(chapter = 1, positionMs = 10_000)

        val first = playerUiState(at, null, EndPanelState(), PlayerSettings(), rows).chapters
        val moved = playerUiState(at.copy(positionMs = 10_500), null, EndPanelState(), PlayerSettings(), rows).chapters
        val next = playerUiState(at.copy(chapterIndex = 2), null, EndPanelState(), PlayerSettings(), rows).chapters

        assertThat(moved).isSameInstanceAs(first)
        assertThat(next).isNotSameInstanceAs(first)
        assertThat(next.map { it.current }).containsExactly(false, false, true).inOrder()
    }

    @Test
    fun `the book, the chapter and its times`() {
        val state = map(SessionBooks.at(chapter = 1, positionMs = 10_000))

        assertThat(state.bookId).isEqualTo(BookId("42"))
        assertThat(state.title).isEqualTo("The Long Road")
        assertThat(state.credits).isEqualTo(
            CreditsLine.AuthorsAndNarrators(Shortened("Anne Verlaine", null, 0), Shortened("Thomas Rivière", null, 0)),
        )
        assertThat(state.coverUrl).isEqualTo("https://books.example/cover/42")
        assertThat(state.chapterIndex).isEqualTo(1)
        assertThat(state.chapterTitle).isEqualTo("Middle")
        assertThat(state.chapterElapsedMs).isEqualTo(10_000)
        assertThat(state.chapterDurationMs).isEqualTo(40_000)
        assertThat(state.chapterCount).isEqualTo(3)
        assertThat(state.bookTimeMs).isEqualTo(70_000)
        assertThat(state.totalMs).isEqualTo(160_000)
    }

    @Test
    fun `a book in parts says so, a book in chapters and no book do not`() {
        val parts = PlayerSessionExtras(SessionBooks.book.copy(inParts = true))

        assertThat(map(SessionBooks.at(extras = parts)).inParts).isTrue()
        assertThat(map(SessionBooks.at()).inParts).isFalse()
        assertThat(map(SessionSnapshot()).inParts).isFalse()
        assertThat(PlayerUiState().inParts).isFalse()
    }

    @Test
    fun `the book's percentage and its time left at the speed`() {
        val state = map(SessionBooks.at(chapter = 1, positionMs = 10_000, speed = 2f))

        assertThat(state.bookPercent).isWithin(0.001).of(43.75)
        assertThat(state.bookTimeLeftMs).isEqualTo(45_000)
        assertThat(state.speed).isEqualTo(2f)
    }

    @Test
    fun `chapters before the current one are heard, each row with its start and its manifest id`() {
        val state = map(SessionBooks.at(chapter = 1))

        assertThat(state.chapters).containsExactly(
            ChapterRow("Opening", 60_000, heard = true, current = false, startMs = 0, chapterId = "c1"),
            ChapterRow("Middle", 40_000, heard = false, current = true, startMs = 60_000, chapterId = "c2"),
            ChapterRow("Closing", 60_000, heard = false, current = false, startMs = 100_000, chapterId = "c3"),
        ).inOrder()
    }

    @Test
    fun `a position past a known chapter end stays inside the chapter`() {
        val state = map(SessionBooks.at(chapter = 0, positionMs = 75_000))

        assertThat(state.chapterElapsedMs).isEqualTo(60_000)
        assertThat(state.bookTimeMs).isEqualTo(60_000)
    }

    @Test
    fun `without every duration there is no percentage, no time left and no total`() {
        val book = SessionBooks.book.copy(durationsKnown = false)
        val snapshot = SessionBooks.at(chapter = 2, positionMs = 90_000, extras = PlayerSessionExtras(book))
            .let { it.copy(chapters = it.chapters.dropLast(1) + SessionChapter("Closing", 100_000, null)) }

        val state = map(snapshot)

        assertThat(state.bookPercent).isNull()
        assertThat(state.bookTimeLeftMs).isNull()
        assertThat(state.totalMs).isNull()
        assertThat(state.chapterDurationMs).isNull()
        assertThat(state.chapterElapsedMs).isEqualTo(90_000)
        assertThat(state.chapters.last().durationMs).isNull()
    }

    @Test
    fun `no book, nothing to show`() {
        val state = map(SessionSnapshot(chapters = SessionBooks.chapters, playWhenReady = true))

        assertThat(state).isEqualTo(PlayerUiState())
    }

    @Test
    fun `what the session says beyond the player`() {
        val sleep = SleepState(SleepChoice.Minutes(15), 600_000, fading = false)
        val extras = PlayerSessionExtras(
            SessionBooks.book, loading = true, loadFailed = true, offline = true, unreachable = true, sleep = sleep,
        )

        val state = map(SessionBooks.at(extras = extras).copy(buffering = true))

        assertThat(state.loading).isTrue()
        assertThat(state.loadFailed).isTrue()
        assertThat(state.offline).isTrue()
        assertThat(state.unreachable).isTrue()
        assertThat(state.sleep).isEqualTo(sleep)
        assertThat(state.playing).isTrue()
        assertThat(state.buffering).isTrue()
    }

    @Test
    fun `at the end of the book it does not play, though play was asked`() {
        val state = map(SessionBooks.at(playing = true).copy(ended = true))

        assertThat(state.playing).isFalse()
    }

    @Test
    fun `the settings come along`() {
        val settings = PlayerSettings(backSeconds = 10, lastSleepChoice = SleepChoice.EndOfChapter)

        assertThat(map(SessionBooks.at(), settings = settings).settings).isEqualTo(settings)
    }

    @Test
    fun `a finished book with its end panel`() {
        val next = NextVolume(BookId("43"), "The Long Road Back", "2")
        val extras = PlayerSessionExtras(SessionBooks.book, finished = FinishedBook(next))
        val end = EndPanelState(rating = 4, sending = true, failed = false, panelSeen = true)

        val state = map(SessionBooks.at(extras = extras), end = end)

        assertThat(state.finished).isEqualTo(FinishedState(4, next, sending = true, failed = false, panelSeen = true))
        assertThat(map(SessionBooks.at(), end = end).finished).isNull()
    }

    @Test
    fun `a newer position is shown for its own book only`() {
        val at = Instant.parse("2026-11-15T08:00:00Z")
        val offer = Offer(BookId("42"), SessionBooks.server(at), 120_000)

        // 2:00 is 20 s into the third chapter, "Closing".
        assertThat(map(SessionBooks.at(), newer = offer).newerPosition)
            .isEqualTo(OfferedListening(120_000, at, OfferedChapter(3, "Closing", 20_000)))
        assertThat(map(SessionBooks.at(), newer = offer.copy(bookId = BookId("7"))).newerPosition).isNull()
    }

    @Test
    fun `a first book being read, or that could not be, shows before the session has a book`() {
        val loading = map(SessionBooks.at(extras = PlayerSessionExtras(book = null, loading = true)))
        val failed = map(SessionBooks.at(extras = PlayerSessionExtras(book = null, loadFailed = true)))

        assertThat(loading.bookId).isNull()
        assertThat(loading.loading).isTrue()
        assertThat(failed.loadFailed).isTrue()
        assertThat(failed.chapters).isEmpty()
    }

    @Test
    fun `the small cover for the mini-player`() {
        val book = SessionBooks.book.copy(artworkUrl = "https://books.example/cover/42/small")

        val state = map(SessionBooks.at(extras = PlayerSessionExtras(book)))

        assertThat(state.artworkUrl).isEqualTo("https://books.example/cover/42/small")
        assertThat(state.coverUrl).isEqualTo("https://books.example/cover/42")
    }
}
