package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** "End of chapter" on a book whose chapters are its files: the ticks against the engine's file changes. */
@RunWith(AndroidJUnit4::class)
class SleepDriverTest {

    /** Three files of 5 minutes, each a chapter: the ends are at 5, 10 and 15 minutes. */
    private val threeParts = AudioManifest(
        bookId = BookId("7"),
        contentVersion = "rev-1",
        title = "A Long Saga",
        authors = listOf("Anne Verlaine"),
        narrators = emptyList(),
        hasCover = false,
        assets = (0..2).map { AudioAsset("p$it", FileId("${200 + it}"), it, "mp3", PartMs, 1_000, null) },
        chapters = (0..2).map { AudioChapter("c$it", "Part ${it + 1}", "p$it", it, it * PartMs, (it + 1) * PartMs, 0) },
        totalDurationMs = 3 * PartMs,
    )

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val timer = SleepTimer(MonotonicClock { 0 })
    private val exactPause = FakeExactPause()
    private val driver = SleepDriver(engine, navigator, timer, exactPause)

    /** Playing [threeParts] from [startMs], "End of chapter" set. */
    private fun endOfChapterFrom(startMs: Long) {
        navigator.open(BookPlaylist.of(threeParts, null, { "Part $it" }) { "https://b/$it" }!!, startMs)
        engine.durationsMs = List(3) { PartMs }
        engine.engineState = Player.STATE_READY
        engine.wantsToPlay = true
        engine.update()
        driver.start(SleepChoice.EndOfChapter, fade = true)
    }

    private val playing: Boolean get() = engine.wantsToPlay

    @Test
    fun `playback crossing into the next file pauses, though the tick sees it before the exact pause`() {
        endOfChapterFrom(290_000)
        driver.tick(299_600, PartMs)
        assertThat(exactPause.target).isEqualTo(PartMs)

        // The engine is in the next file already; neither the exact pause nor its arrival were heard of.
        driver.tick(PartMs + 300, 2 * PartMs)

        assertThat(playing).isFalse()
        assertThat(timer.state.value).isNull()
        assertThat(exactPause.target).isNull()
        assertThat(timer.consumeResumePoint()).isEqualTo(PartMs + 300 - 5_000)
    }

    @Test
    fun `playback crossing into the last chapter on its own pauses too`() {
        endOfChapterFrom(2 * PartMs - 10_000)

        driver.tick(2 * PartMs + 200, 3 * PartMs)

        assertThat(playing).isFalse()
    }

    @Test
    fun `a seek into the next chapter waits for its end`() {
        endOfChapterFrom(100_000)

        driver.onSeek()
        driver.tick(PartMs + 100_000, 2 * PartMs)

        assertThat(playing).isTrue()
        assertThat(exactPause.target).isEqualTo(2 * PartMs)
    }

    @Test
    fun `a rewind back over the chapter's start waits for the end of the chapter it lands in`() {
        // Paused 800 ms into the second part, the timer waiting for its end; the resume goes back 5 s.
        endOfChapterFrom(PartMs + 800)
        assertThat(exactPause.target).isEqualTo(2 * PartMs)

        driver.onSeek()
        driver.tick(PartMs - 4_200, PartMs)

        assertThat(playing).isTrue()
        assertThat(exactPause.target).isEqualTo(PartMs)
        driver.tick(PartMs + 100, 2 * PartMs)
        assertThat(playing).isFalse()
    }

    @Test
    fun `in the last chapter the book ends, no pause`() {
        endOfChapterFrom(3 * PartMs - 10_000)

        driver.tick(3 * PartMs - 500, 3 * PartMs)
        assertThat(exactPause.target).isNull()
        driver.tick(3 * PartMs, 3 * PartMs)

        assertThat(playing).isTrue()
    }

    @Test
    fun `extended, the end of the next chapter is waited for`() {
        endOfChapterFrom(290_000)
        driver.tick(299_600, PartMs)

        driver.extend()
        driver.tick(PartMs + 300, 2 * PartMs)

        assertThat(playing).isTrue()
        assertThat(exactPause.target).isEqualTo(2 * PartMs)
    }

    private companion object {
        const val PartMs = 300_000L
    }
}
