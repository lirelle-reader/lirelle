package app.tfdn.lirelle.feature.player.service

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FocusGuardTest {

    private val engine = FakeEngine().apply {
        items = listOf(MediaItem.fromUri("https://b/a1"))
        engineState = Player.STATE_READY
        wantsToPlay = true
    }

    private fun TestScope.guard() = FocusGuard(engine, backgroundScope).also {
        engine.update()
        settle()
        it.start()
    }

    private fun TestScope.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun TestScope.interrupted(suppressed: Boolean) {
        engine.suppression = if (suppressed) {
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        } else {
            Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }
        engine.update()
        settle()
    }

    @Test
    fun `after a short interruption playback goes on`() = runTest {
        guard()

        interrupted(true)
        advanceTimeBy(90.seconds)
        interrupted(false)
        advanceTimeBy(5.minutes)
        settle()

        assertThat(engine.wantsToPlay).isTrue()
    }

    @Test
    fun `after a long one it stays paused`() = runTest {
        guard()

        interrupted(true)
        advanceTimeBy(2.minutes + 1.seconds)
        settle()
        interrupted(false)

        assertThat(engine.wantsToPlay).isFalse()
    }
}
