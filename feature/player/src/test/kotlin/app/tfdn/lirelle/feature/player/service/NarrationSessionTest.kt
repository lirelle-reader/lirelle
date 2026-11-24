package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReadAlongRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.sampleBookDetail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The session playing a narration (book 42, read-along file 1: [ReadAlongMaps.invented]): sentences at 0, 4,
 * 9 s in section 1 (to 15 s), 15 and 19 s in section 3 (to 25 s).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NarrationSessionTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val audio = FakeAudioRepository().apply {
        manifestResult = ApiResult.Success(Manifests.twoFiles())
        playbackStateResult = ApiResult.Success(null)
        nextVolumeResult = ApiResult.Success(NextVolume(BookId("43"), "The Next Road", "2"))
    }
    private val library = FakeLibraryRepository().apply { bookResult = ApiResult.Success(sampleBookDetail(id = "42")) }
    private val maps = FakeMapStore()
    private val settings = InMemoryPlayerSettingsStore(PlayerSettings(backSeconds = 10, forwardSeconds = 20))
    private val cursor = RecordingCursor()
    private val exactPause = FakeExactPause()
    private val left = mutableListOf<Long?>()
    private lateinit var timer: SleepTimer
    private lateinit var sleep: SleepDriver

    private fun TestScope.session(): BookSession {
        val clock = MonotonicClock { currentTime }
        timer = SleepTimer(clock)
        sleep = SleepDriver(engine, navigator, timer, exactPause)
        val loader = BookLoader(audio, library, InMemoryPlaybackStore(), settings) { "Part $it" }
        val narrationLoader = NarrationLoader(
            maps, library, FakeReaderRepository(), InMemoryNarrationStore(), settings, FakeReadAlongRepository(),
            nothingKept(),
        )
        return BookSession(
            engine, navigator, loader, sleep, timer, settings,
            { series, book -> (audio.nextAudioVolume(series, book) as? ApiResult.Success)?.value },
            clock, backgroundScope,
        ).also {
            it.leaving = { left += navigator.bookTimeMs() }
            it.narrations = NarrationParts(narrationLoader, cursor)
            runCurrent()
        }
    }

    private fun TestScope.settle() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun TestScope.narrating(): BookSession = session().also {
        it.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()
        engine.durationsMs = listOf(15_000, 10_000)
        engine.update()
        settle()
    }

    private fun TestScope.at(session: BookSession, bookTimeMs: Long) {
        session.handle(PlayerCommand.SeekBookTime(bookTimeMs))
        settle()
    }

    @Test
    fun `loading a narration gives the engine its segments and says it is one`() = runTest {
        val session = narrating()

        assertThat(engine.items).hasSize(2)
        assertThat(engine.items[1].clippingConfiguration.startPositionMs).isEqualTo(2_000)
        assertThat(engine.wantsToPlay).isTrue()
        assertThat(session.extras.value.narrationFileId).isEqualTo(FileId("1"))
        assertThat(session.extras.value.book!!.id).isEqualTo(BookId("42"))
        assertThat(session.extras.value.book!!.totalMs).isEqualTo(25_000)
        assertThat(session.extras.value.loading).isFalse()
    }

    @Test
    fun `a narration that cannot be had says so and the book playing stays`() = runTest {
        val session = session()
        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        maps.map = null

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()

        assertThat(session.extras.value.loadFailed).isTrue()
        assertThat(session.extras.value.narrationFileId).isNull()
        assertThat(left).isEmpty()
    }

    @Test
    fun `a narration whose book page cannot be read says the load failed`() = runTest {
        val session = session()
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()

        assertThat(session.extras.value.loading).isFalse()
        assertThat(session.extras.value.loadFailed).isTrue()
    }

    @Test
    fun `a narration whose map breaks while it is read says the load failed, not loading for ever`() = runTest {
        val session = session()
        maps.failure = IllegalArgumentException("a map that cannot be")

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()

        assertThat(session.extras.value.loading).isFalse()
        assertThat(session.extras.value.loadFailed).isTrue()
    }

    @Test
    fun `the sentence steps go to the previous and next sentences`() = runTest {
        val session = narrating()
        at(session, 6_000)

        session.handle(PlayerCommand.NextSentence)
        assertThat(navigator.bookTimeMs()).isEqualTo(9_000)
        session.handle(PlayerCommand.NextSentence)
        assertThat(navigator.bookTimeMs()).isEqualTo(15_000)
        at(session, 17_000)
        session.handle(PlayerCommand.PreviousSentence)
        assertThat(navigator.bookTimeMs()).isEqualTo(15_000)
        // In the first seconds of a sentence: the one before.
        session.handle(PlayerCommand.PreviousSentence)
        assertThat(navigator.bookTimeMs()).isEqualTo(9_000)
    }

    @Test
    fun `there is no sentence after the last one`() = runTest {
        val session = narrating()
        at(session, 20_000)
        val seeks = engine.seeks.size

        session.handle(PlayerCommand.NextSentence)

        assertThat(engine.seeks).hasSize(seeks)
        assertThat(navigator.bookTimeMs()).isEqualTo(20_000)
    }

    @Test
    fun `a narration still skips by the seconds of the settings`() = runTest {
        val session = narrating()
        at(session, 2_000)

        session.handle(PlayerCommand.Forward)
        assertThat(navigator.bookTimeMs()).isEqualTo(22_000)
        session.handle(PlayerCommand.Back)
        assertThat(navigator.bookTimeMs()).isEqualTo(12_000)
    }

    @Test
    fun `the sentence steps do nothing to an audiobook`() = runTest {
        val session = session()
        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        at(session, 50_000)

        session.handle(PlayerCommand.NextSentence)
        session.handle(PlayerCommand.PreviousSentence)

        assertThat(navigator.bookTimeMs()).isEqualTo(50_000)
    }

    @Test
    fun `play after a pause takes the narration up at the start of its sentence`() = runTest {
        val session = narrating()
        at(session, 6_000)
        session.play()
        assertThat(navigator.bookTimeMs()).isEqualTo(6_000)
        engine.positionMs = 7_500
        engine.update()

        session.onPaused()
        advanceTimeBy(60_000)
        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(4_000)
    }

    @Test
    fun `play after the sleep timer's end of section plays on from the next section`() = runTest {
        val session = narrating()
        at(session, 10_000)
        session.handle(PlayerCommand.StartSleep(SleepChoice.EndOfChapter))
        settle()
        // Playback reaches the end of section 1 (15 s), in its last sentence, where the engine pauses.
        at(session, 14_990)
        exactPause.reach()
        settle()
        session.onPaused()

        session.play()

        // Not the last sentence again (9 s): section 3, which follows.
        assertThat(navigator.bookTimeMs()).isEqualTo(15_000)
    }

    @Test
    fun `a stop at the end of a section just into the next one plays on from that next one`() = runTest {
        val session = narrating()
        at(session, 10_000)
        session.handle(PlayerCommand.StartSleep(SleepChoice.EndOfChapter))
        settle()
        // The end of a section that ends its segment is reached a millisecond into the next segment.
        at(session, 15_001)
        exactPause.reach()
        settle()
        session.onPaused()

        session.play()

        assertThat(navigator.bookTimeMs()).isEqualTo(15_000)
    }

    @Test
    fun `play after a timed sleep takes the narration up at the sentence before the fade`() = runTest {
        val session = narrating()
        at(session, 17_000)
        session.handle(PlayerCommand.StartSleep(SleepChoice.Minutes(1)))
        settle()
        // The fade begins at once (one minute left), at 17 s; the pause comes a minute of ticks later, at 22 s.
        sleep.tick(17_000, 25_000)
        at(session, 22_000)
        repeat(130) {
            advanceTimeBy(500)
            if (engine.wantsToPlay) sleep.tick(22_000, 25_000)
        }
        settle()
        assertThat(engine.wantsToPlay).isFalse()
        session.onPaused()

        session.play()

        // 5 s before the fade (12 s) is in the sentence started at 9 s.
        assertThat(navigator.bookTimeMs()).isEqualTo(9_000)
    }

    @Test
    fun `a narration's speed is kept apart from the book's`() = runTest {
        settings.speeds[BookId("42")] = 1.5f
        val session = narrating()
        assertThat(engine.speed).isEqualTo(1.5f)

        session.handle(PlayerCommand.SetSpeed(1.27f))
        settle()

        assertThat(engine.speed).isEqualTo(1.25f)
        assertThat(settings.narrationSpeeds[BookId("42")]).isEqualTo(1.25f)
        assertThat(settings.speeds[BookId("42")]).isEqualTo(1.5f)
    }

    @Test
    fun `the end of a narration is finished, told to the reader, with no next volume`() = runTest {
        val session = narrating()

        session.onFinished()
        settle()

        assertThat(session.extras.value.narrationFinished).isTrue()
        assertThat(session.extras.value.finished).isNull()
        assertThat(cursor.finished.value).isEqualTo(BookId("42"))
        assertThat(audio.calls.filter { it.startsWith("nextAudioVolume") }).isEmpty()
    }

    @Test
    fun `play on a narration at its end starts it over from its first sentence`() = runTest {
        val session = narrating()
        at(session, 24_000)
        session.onFinished()
        engine.engineState = Player.STATE_ENDED
        engine.wantsToPlay = false
        engine.update()
        settle()

        session.play()
        settle()

        assertThat(navigator.bookTimeMs()).isEqualTo(0)
        assertThat(engine.wantsToPlay).isTrue()
        assertThat(session.extras.value.narrationFinished).isFalse()
        assertThat(cursor.finished.value).isNull()
    }

    @Test
    fun `a new load clears the end of a narration, a seek away from it too`() = runTest {
        val session = narrating()
        session.onFinished()
        settle()

        at(session, 3_000)
        assertThat(session.extras.value.narrationFinished).isFalse()
        assertThat(cursor.finished.value).isNull()

        session.onFinished()
        engine.engineState = Player.STATE_ENDED
        engine.update()
        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()
        assertThat(session.extras.value.narrationFinished).isFalse()
    }

    @Test
    fun `a narration replaces a playing audiobook, whose position goes out first`() = runTest {
        val session = session()
        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()
        at(session, 33_000)

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()

        assertThat(left).containsExactly(33_000L)
        assertThat(session.extras.value.narrationFileId).isEqualTo(FileId("1"))
        assertThat(engine.items).hasSize(2)
        assertThat(engine.items[0].clippingConfiguration.endPositionMs).isEqualTo(15_000)
    }

    @Test
    fun `an audiobook replaces a playing narration, whose position goes out first`() = runTest {
        val session = narrating()
        at(session, 6_000)

        session.handle(PlayerCommand.LoadBook(BookId("42")))
        settle()

        assertThat(left).containsExactly(6_000L)
        assertThat(session.extras.value.narrationFileId).isNull()
        assertThat(audio.calls).contains("manifest 42")
    }

    @Test
    fun `the narration held, paused, plays on without being loaded again`() = runTest {
        val session = narrating()
        at(session, 6_000)
        engine.wantsToPlay = false
        engine.update()
        val asked = maps.asked.size
        val prepares = engine.prepares

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1")))
        settle()

        assertThat(engine.wantsToPlay).isTrue()
        assertThat(navigator.bookTimeMs()).isEqualTo(6_000)
        assertThat(maps.asked).hasSize(asked)
        assertThat(engine.prepares).isEqualTo(prepares)
        assertThat(left).isEmpty()
    }

    @Test
    fun `the narration held, asked at a sentence, goes there without being loaded again`() = runTest {
        val session = narrating()
        at(session, 6_000)
        val asked = maps.asked.size

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = 3, fragment = "s5"))
        settle()

        assertThat(navigator.bookTimeMs()).isEqualTo(19_000)
        assertThat(maps.asked).hasSize(asked)
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `a narration asked at a section without a sentence opens at the section's first`() = runTest {
        val session = session()

        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = 3))
        settle()

        assertThat(navigator.bookTimeMs()).isEqualTo(15_000)
        at(session, 20_000)
        // Held already: it goes there rather than loading again.
        session.handle(PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = 1))
        settle()
        assertThat(navigator.bookTimeMs()).isEqualTo(0)
    }

    @Test
    fun `a narration asked at a sentence opens there`() = runTest {
        val session = session()

        session.handle(
            PlayerCommand.LoadNarration(BookId("42"), FileId("1"), sectionIndex = 1, fragment = "s3", play = false),
        )
        settle()

        assertThat(navigator.bookTimeMs()).isEqualTo(9_000)
        assertThat(engine.wantsToPlay).isFalse()
        assertThat(session.extras.value.narrationFileId).isEqualTo(FileId("1"))
    }
}
