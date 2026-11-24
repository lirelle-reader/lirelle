package app.tfdn.lirelle.feature.player.service

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.robolectric.Robolectric
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A play button with nothing loaded, as the service answers it, through to the session's player. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResumptionTest {

    private val lastBooks = lastBookStore()
    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }

    private val service = Robolectric.buildService(OwedService::class.java).create().get()
    private val debt = ForegroundDebt(service) { PlayerNotification.startingNotification(service) }

    private lateinit var book: BookSession

    /** The start the media button receiver makes. */
    private val playButtonStart =
        PlaybackButtonReceiver.markPlayButtonStart(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON))

    private fun TestScope.player(scope: kotlinx.coroutines.CoroutineScope = backgroundScope): ChapterPlayer {
        val clock = MonotonicClock { currentTime }
        val timer = SleepTimer(clock)
        val settings = InMemoryPlayerSettingsStore()
        val loader = BookLoader(audio, FakeLibraryRepository(), InMemoryPlaybackStore(), settings) { "Part $it" }
        book = BookSession(
            engine, navigator, loader, SleepDriver(engine, navigator, timer, FakeExactPause()), timer,
            InMemoryPlayerSettingsStore(), { _, _ -> null }, clock, scope,
        )
        book.resumable = { lastBooks.get()?.bookId }
        debt.follow(book)
        return ChapterPlayer(engine, navigator, book)
    }

    private fun TestScope.settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
        }
    }

    /** [left]: the book is no longer the last one by the time the service comes to load it, and is rejected. */
    private suspend fun pressPlay(player: ChapterPlayer, left: Boolean = false): ChapterPlayer {
        val items = resumptionItems(lastBooks)!!
        if (left) lastBooks.clear()
        player.setMediaItems(items.mediaItems, items.startIndex, items.startPositionMs)
        return player
    }

    @Test
    fun `a book that is no longer the last one shows as starting, then leaves an empty player`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        val player = player()

        pressPlay(player, left = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()

        settle()
        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(player.playWhenReady).isFalse()
        assertThat(audio.calls).doesNotContain("manifest 42")
    }

    @Test
    fun `the last book is loaded and played`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        val player = player()

        pressPlay(player)
        settle()

        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `without a last book there is nothing to resume`() = runTest {
        lastBooks.clear()

        assertThat(resumptionItems(lastBooks)).isNull()
    }

    @Test
    fun `a last book that cannot be read resumes nothing and asks the server nothing`() = runTest {
        val unreadable = object : LastBookStore {
            override suspend fun get(): LastBook? = error("The last book's file is damaged")

            override suspend fun put(book: LastBook) = Unit

            override suspend fun clear() = Unit
        }
        player()

        assertThat(resumptionItems(unreadable)).isNull()
        settle()
        assertThat(audio.calls).isEmpty()
    }

    @Test
    fun `a book rejected at once still enters the foreground the play button owed, and leaves it`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        // The service's scope runs at once, as Dispatchers.Main.immediate does: the rejection is instant.
        val player = player(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        debt.onStart(playButtonStart)

        pressPlay(player, left = true)
        shadowOf(Looper.getMainLooper()).idle()

        val shadow = shadowOf(service)
        assertThat(shadow.lastForegroundNotificationId).isNotEqualTo(0)
        assertThat(shadow.isForegroundStopped).isTrue()
        assertThat(shadow.isStoppedBySelf).isTrue()
        assertThat(player.currentTimeline.isEmpty).isTrue()
    }

    @Test
    fun `nothing is owed when the service was not started by a play button`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        val player = player(kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        pressPlay(player, left = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(0)
    }

    @Test
    fun `a book that loads pays nothing, Media3 enters the foreground with it`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        val player = player()
        debt.onStart(playButtonStart)

        pressPlay(player)
        settle()

        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(0)
    }

    @Test
    fun `a button of Media3's notification pressed with no book loaded owes the foreground too`() {
        debt.onStart(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON), noBook = true)
        debt.settle()

        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    private fun entered() = shadowOf(service).lastForegroundNotificationId != 0

    private fun left() = shadowOf(service).isForegroundStopped && shadowOf(service).isStoppedBySelf

    @Test
    fun `a play button's start owes the foreground out of it, not in it, and Media3's own starts never`() {
        debt.onStart(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON))
        debt.settle()
        assertThat(entered()).isFalse()
        // A dismissal, or any other start that is no media button, owes nothing even with no book.
        debt.onStart(android.content.Intent("other"), noBook = true)
        debt.settle()
        assertThat(entered()).isFalse()

        debt.onStart(playButtonStart)
        debt.settle()
        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    @Test
    fun `a service already in the foreground owes nothing`() {
        val media = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        service.startForeground(1001, PlayerNotification.startingNotification(service), media)

        debt.onStart(playButtonStart)
        debt.settle()

        assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(1001)
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
    }

    @Test
    fun `a book rejected later pays the debt`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        val player = player()
        debt.onStart(playButtonStart)

        pressPlay(player, left = true)
        settle()

        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    @Test
    fun `a book that cannot be loaded for the network pays the debt`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        audio.manifestResult = ApiResult.Failure(app.tfdn.lirelle.core.network.ApiError.ServerUnreachable)
        val player = player()
        debt.onStart(playButtonStart)

        pressPlay(player)
        settle()

        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    @Test
    fun `a load cancelled by another command settles nothing, and the safety check settles later`() = runTest {
        lastBooks.put(LastBook(BookId("42"), "The Long Road", null))
        audio.gate = kotlinx.coroutines.CompletableDeferred()
        val player = player()
        debt.onStart(playButtonStart)

        pressPlay(player)
        settle()
        book.handle(PlayerCommand.CloseBook)
        settle()
        assertThat(entered()).isFalse()

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ForegroundDebt.SafetyDelayMs))
        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    @Test
    fun `a debt nothing answers is paid after a few seconds`() {
        debt.onStart(playButtonStart)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ForegroundDebt.SafetyDelayMs - 1))
        assertThat(entered()).isFalse()

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1))

        assertThat(entered()).isTrue()
        assertThat(left()).isTrue()
    }

    @Test
    fun `a debt left when the service ends is never checked after it`() {
        debt.onStart(playButtonStart)

        debt.release()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ForegroundDebt.SafetyDelayMs * 2))

        assertThat(entered()).isFalse()
    }
}

/** A service to watch the foreground calls on. */
class OwedService : android.app.Service() {
    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null
}
