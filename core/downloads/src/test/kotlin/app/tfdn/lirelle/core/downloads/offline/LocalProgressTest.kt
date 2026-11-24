package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.downloads.engine.Books
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The book of [Books.book]. */
private val TheBook = BookId("3")
private val OtherBook = BookId("4")
private val TextFile = FileId("31")
private val AlongFile = FileId("32")
private const val BookLengthMs = 1_000_000L

private fun at(minute: Int): Instant = Instant.parse("2026-11-16T10:00:00Z").plusSeconds(minute * 60L)

private fun text(percent: Double, minute: Int, fileId: FileId = TextFile) =
    LocalPosition(fileId, "epubcfi(/6/4)", percent, at(minute))

private fun narration(percent: Double, minute: Int) =
    LocalNarration(AlongFile, NarrationPlace(12.0, "chapter.xhtml#s3", 1), percent, at(minute))

private fun listened(bookTimeMs: Long, minute: Int, bookId: BookId = TheBook) = LocalPlayback(
    bookId = bookId,
    assetId = "aud_one",
    positionMs = bookTimeMs,
    bookTimeMs = bookTimeMs,
    at = at(minute),
    contentVersion = "a".repeat(64),
)

class LocalProgressTest {

    @get:Rule
    internal val fixture = KeptFixture()

    private val positions = InMemoryPositionStore()
    private val outbox = InMemoryOutbox()
    private val narrations = InMemoryNarrations()
    private val playback = InMemoryPlaybacks()
    private val playbackOutbox = InMemoryPlaybackQueue()
    private val book = DownloadedBook(TheBook, emptyList(), at(0), lastOpenedAt = null)
    private val detail =
        Books.book(Books.epub(31), Books.readAlong(32, primary = false), Books.audio(33, null))
    private val manifest = Recorded.manifest("mp3").copy(bookId = TheBook, totalDurationMs = BookLengthMs)

    private fun progress() = LocalProgress(positions, outbox, narrations, playback, playbackOutbox, fixture.kept())

    @Before
    fun keepThePage() = runBlocking { fixture.kept().keep(detail) }

    @Test
    fun `a book read only has the percent of its text`() = runBlocking<Unit> {
        positions.put(text(41.6, minute = 1))

        assertThat(progress().percentOf(book)).isEqualTo(42)
    }

    @Test
    fun `a book listened to only has its time over the length of the kept manifest`() = runBlocking<Unit> {
        fixture.kept().keepManifest(manifest)
        playback.put(listened(250_000, minute = 1))

        assertThat(progress().percentOf(book)).isEqualTo(25)
    }

    @Test
    fun `the most recent of the text and the audiobook counts`() = runBlocking<Unit> {
        fixture.kept().keepManifest(manifest)
        positions.put(text(40.0, minute = 1))
        playback.put(listened(700_000, minute = 2))
        assertThat(progress().percentOf(book)).isEqualTo(70)

        positions.put(text(45.0, minute = 3))
        assertThat(progress().percentOf(book)).isEqualTo(45)
    }

    @Test
    fun `a position not sent yet, newer than the stored one, counts`() = runBlocking<Unit> {
        positions.put(text(40.0, minute = 1))
        outbox.enqueuePosition(text(55.0, minute = 2))

        assertThat(progress().percentOf(book)).isEqualTo(55)
    }

    @Test
    fun `a stored position newer than the one still waiting counts`() = runBlocking<Unit> {
        outbox.enqueuePosition(text(55.0, minute = 1))
        positions.put(text(60.0, minute = 2))

        assertThat(progress().percentOf(book)).isEqualTo(60)
    }

    @Test
    fun `an audiobook position not sent yet counts`() = runBlocking<Unit> {
        fixture.kept().keepManifest(manifest)
        playback.put(listened(100_000, minute = 1))
        playbackOutbox.enqueue(listened(300_000, minute = 2))
        playbackOutbox.enqueue(listened(900_000, minute = 9, bookId = OtherBook))

        assertThat(progress().percentOf(book)).isEqualTo(30)
    }

    @Test
    fun `the listening is the device's latest place, as a share of the kept manifest`() = runBlocking<Unit> {
        fixture.kept().keepManifest(manifest)
        playback.put(listened(100_000, minute = 1))
        playbackOutbox.enqueue(listened(14_000, minute = 2))

        val listening = progress().listeningOf(book)!!

        assertThat(listening.percentage).isWithin(0.001).of(1.4)
        assertThat(listOf(listening.assetId, listening.positionMs, listening.capturedAt, listening.completed))
            .isEqualTo(listOf("aud_one", 14_000L, at(2), false))
    }

    @Test
    fun `a book never listened to on the device, or without its manifest, has no listening`() = runBlocking<Unit> {
        assertThat(progress().listeningOf(book)).isNull()

        playback.put(listened(100_000, minute = 1))
        assertThat(progress().listeningOf(book)).isNull()

        fixture.kept().keepManifest(manifest)
        assertThat(progress().listeningOf(book.copy(bookId = OtherBook))).isNull()
    }

    @Test
    fun `a narration newer than the text counts`() = runBlocking<Unit> {
        positions.put(text(40.0, minute = 1))
        positions.put(text(42.0, minute = 2, fileId = AlongFile))
        narrations.put(narration(64.0, minute = 3))
        assertThat(progress().percentOf(book)).isEqualTo(64)

        outbox.enqueueNarration(narration(66.0, minute = 4))
        assertThat(progress().percentOf(book)).isEqualTo(66)
    }

    @Test
    fun `the text of another file of the book counts as well`() = runBlocking<Unit> {
        positions.put(text(40.0, minute = 1))
        positions.put(text(12.0, minute = 2, fileId = AlongFile))

        assertThat(progress().percentOf(book)).isEqualTo(12)
    }

    @Test
    fun `without a kept manifest the audiobook is left out`() = runBlocking<Unit> {
        positions.put(text(40.0, minute = 1))
        playback.put(listened(700_000, minute = 2))

        assertThat(progress().percentOf(book)).isEqualTo(40)
    }

    @Test
    fun `the positions of other books are not this one's`() = runBlocking<Unit> {
        positions.put(text(40.0, minute = 1, fileId = FileId("99")))
        playback.put(listened(700_000, minute = 2, bookId = OtherBook))

        assertThat(progress().percentOf(book)).isNull()
    }

    @Test
    fun `a listening past the end is the whole book`() = runBlocking<Unit> {
        fixture.kept().keepManifest(manifest)
        playback.put(listened(BookLengthMs + 5_000, minute = 1))

        assertThat(progress().percentOf(book)).isEqualTo(100)
    }

    @Test
    fun `with nothing on the device, what the server said last counts`() = runBlocking<Unit> {
        fixture.kept().keep(detail, readingPercent = 37, readAt = at(-60))

        assertThat(progress().percentOf(book)).isEqualTo(37)
    }

    @Test
    fun `a position of the device counts before what the server said`() = runBlocking<Unit> {
        fixture.kept().keep(detail, readingPercent = 37, readAt = at(5))
        positions.put(text(20.0, minute = 1))

        assertThat(progress().percentOf(book)).isEqualTo(20)
    }

    @Test
    fun `a book with no position anywhere has no progress`() = runBlocking<Unit> {
        assertThat(progress().percentOf(book)).isNull()
    }

    @Test
    fun `the device's own progress says when it was made, and what the server said last is none of it`() =
        runBlocking<Unit> {
            fixture.kept().keepManifest(manifest)
            fixture.kept().keep(detail, readingPercent = 37, readAt = at(5))
            assertThat(progress().ownOf(book)).isNull()

            positions.put(text(20.0, minute = 1))
            outbox.enqueuePosition(text(55.4, minute = 3))
            playback.put(listened(250_000, minute = 2))

            assertThat(progress().ownOf(book)).isEqualTo(OwnProgress(55, at(3)))
        }
}
