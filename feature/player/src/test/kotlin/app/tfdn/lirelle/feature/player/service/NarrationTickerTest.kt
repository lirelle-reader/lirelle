package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.narration.DefaultNarrationCursor
import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.audio.narration.toManifest
import app.tfdn.lirelle.core.audio.narration.trackOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A cursor that keeps every call made to it. */
class RecordingCursor(private val inner: NarrationCursor = DefaultNarrationCursor()) : NarrationCursor by inner {
    val published = mutableListOf<HeardSentence?>()
    val announced = mutableListOf<SkippedNotice>()

    override fun publish(sentence: HeardSentence?) {
        published += sentence
        inner.publish(sentence)
    }

    override fun announce(notice: SkippedNotice) {
        announced += notice
        inner.announce(notice)
    }
}

/** A narration of [map] for book [bookId], as the loader makes it. */
fun narrationOf(map: ReadAlongMap, bookId: BookId = BookId("42"), startMs: Long = 0, speed: Float = 1f): LoadedBook {
    val playlist = BookPlaylist.ofSegments(
        map.toManifest("The Long Road", emptyList(), emptyList(), hasCover = false),
        null,
        { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
    ) { "https://b/$it" }!!
    return LoadedBook(bookId, playlist, null, startMs, speed, BookSource.Narration(map.fileId, map))
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NarrationTickerTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val cursor = RecordingCursor()

    private fun TestScope.ticker(): NarrationTicker =
        NarrationTicker(engine, navigator, cursor, backgroundScope).also { it.start() }

    /** [book] opened in the engine at its start, playing, and followed. */
    private fun TestScope.open(ticker: NarrationTicker, book: LoadedBook) {
        navigator.open(book.playlist, book.startMs)
        engine.durationsMs = book.playlist.assets.map { it.durationMs ?: 0 }
        engine.engineState = Player.STATE_READY
        engine.wantsToPlay = true
        engine.update()
        settle()
        ticker.follow(book)
        runCurrent()
    }

    private fun TestScope.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    /** Playback reaches [bookTimeMs] by playing (no seek), then a few ticks go by. */
    private fun TestScope.playTo(bookTimeMs: Long) {
        val place = navigator.playlist!!.itemPositionOf(bookTimeMs)!!
        engine.index = place.index
        engine.positionMs = place.positionMs
        engine.update()
        settle()
        advanceTimeBy(350)
        runCurrent()
    }

    @Test
    fun `at the end of the narration its last sentence is told, no longer playing`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))
        playTo(20_000)

        // The engine a few milliseconds past the last segment's end: the timeline holds it at the narration's end.
        engine.index = 1
        engine.positionMs = 10_040
        engine.engineState = Player.STATE_ENDED
        engine.update()
        settle()

        assertThat(cursor.published.last())
            .isEqualTo(HeardSentence(BookId("42"), FileId("1"), 3, "s5", "text/three.xhtml", false))
    }

    @Test
    fun `the sentence heard is told when it changes, and once more when playback pauses`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))

        playTo(1_000)
        playTo(2_000)
        playTo(5_000)
        engine.wantsToPlay = false
        engine.update()
        settle()
        advanceTimeBy(350)
        runCurrent()

        assertThat(cursor.published).containsExactly(
            HeardSentence(BookId("42"), FileId("1"), 1, "s1", "text/one.xhtml", playing = true),
            HeardSentence(BookId("42"), FileId("1"), 1, "s2", "text/one.xhtml", playing = true),
            HeardSentence(BookId("42"), FileId("1"), 1, "s2", "text/one.xhtml", playing = false),
        ).inOrder()
        assertThat(cursor.sentence.value)
            .isEqualTo(HeardSentence(BookId("42"), FileId("1"), 1, "s2", "text/one.xhtml", false))
    }

    @Test
    fun `paused, it does not keep looking, and a seek is still told`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val ticker = NarrationTicker(engine, navigator, cursor, scope).also { it.start() }
        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))
        assertThat(scope.coroutineContext.job.children.count { it.isActive }).isEqualTo(1)

        engine.wantsToPlay = false
        engine.update()
        settle()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(scope.coroutineContext.job.children.count { it.isActive }).isEqualTo(0)

        navigator.seekBookTime(10_000)
        settle()
        assertThat(cursor.published.last())
            .isEqualTo(HeardSentence(BookId("42"), FileId("1"), 1, "s3", "text/one.xhtml", false))
    }

    @Test
    fun `an audiobook tells nothing`() = runTest {
        val ticker = ticker()
        val audiobook = BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!
        navigator.open(audiobook, 0)

        ticker.follow(LoadedBook(BookId("42"), audiobook, null, 0, 1f))
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(cursor.published).isEmpty()
    }

    @Test
    fun `leaving the narration for an audiobook, or for nothing, tells there is no sentence`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))
        val audiobook = BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!
        navigator.open(audiobook, 0)

        ticker.follow(LoadedBook(BookId("43"), audiobook, null, 0, 1f))
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(cursor.published.last()).isNull()
        assertThat(cursor.published.filterNotNull()).hasSize(1)

        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))
        ticker.follow(null)
        assertThat(cursor.published.last()).isNull()
    }

    @Test
    fun `playing past a section without narration announces it`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap(skippedBytes = 10_000)))

        playTo(14_000)
        playTo(16_000)

        assertThat(cursor.announced).containsExactly(SkippedNotice(BookId("42"), 2, title = null))
    }

    @Test
    fun `playing past a title page announces nothing`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap(skippedBytes = 2_000)))

        playTo(14_000)
        playTo(16_000)

        assertThat(cursor.announced).isEmpty()
    }

    @Test
    fun `a seek past a section without narration announces nothing`() = runTest {
        val ticker = ticker()
        open(ticker, narrationOf(ReadAlongMaps.inventedMap(skippedBytes = 10_000)))
        playTo(14_000)

        navigator.seekBookTime(16_000)
        settle()
        advanceTimeBy(350)
        runCurrent()

        assertThat(cursor.published.last()!!.sectionIndex).isEqualTo(3)
        assertThat(cursor.announced).isEmpty()
    }

    @Test
    fun `the recorded book's chapter without narration is announced when the narration goes past it`() = runTest {
        val gap = ReadAlongMaps.map("gap")
        val ticker = ticker()
        open(ticker, narrationOf(gap, bookId = gap.bookId))
        val sixth = gap.sections.single { it.index == 6 }

        playTo(sixth.startMs - 1_000)
        playTo(sixth.startMs + 1_000)

        assertThat(cursor.announced).containsExactly(SkippedNotice(gap.bookId, 5, title = null))
    }

    @Test
    fun `a new book ends the end of a narration`() = runTest {
        val ticker = ticker()
        cursor.finish(BookId("42"))

        open(ticker, narrationOf(ReadAlongMaps.inventedMap()))

        assertThat(cursor.finished.value).isNull()
    }
}
