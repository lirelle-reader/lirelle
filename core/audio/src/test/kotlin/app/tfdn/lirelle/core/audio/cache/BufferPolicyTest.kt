package app.tfdn.lirelle.core.audio.cache

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

@OptIn(UnstableApi::class)
class BufferPolicyTest {

    @Test
    fun `ten minutes are buffered ahead on a metered network`() {
        val durations = BufferPolicy.durations(isUnmetered = false)
        assertThat(durations.targetBuffer).isEqualTo(10.minutes)
        assertThat(durations.backBuffer).isEqualTo(2.minutes)
    }

    @Test
    fun `thirty minutes are buffered ahead on an unmetered network`() {
        assertThat(BufferPolicy.durations(isUnmetered = true).targetBuffer).isEqualTo(30.minutes)
    }

    private val timeline = SinglePeriodTimeline(1.hours.inWholeMicroseconds, true, false, false, null, MediaItem.EMPTY)

    private fun params(buffered: Duration) = LoadControl.Parameters(
        PlayerId.UNSET,
        timeline,
        MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
        0,
        buffered.inWholeMicroseconds,
        1f,
        true,
        false,
        0,
        0,
    )

    @Test
    fun `a metered network stops loading at ten minutes, an unmetered one goes on`() {
        var unmetered = false
        val control = BufferPolicy.loadControl { unmetered }
        control.onPrepared(PlayerId.UNSET)

        assertThat(control.shouldContinueLoading(params(15.minutes))).isFalse()
        unmetered = true
        assertThat(control.shouldContinueLoading(params(15.minutes))).isTrue()
    }

    @Test
    fun `the calls ExoPlayer makes on a track selection reach the default control`() {
        val control = BufferPolicy.loadControl { false }
        control.onPrepared(PlayerId.UNSET)

        // ExoPlayer 1.11 calls this overload; a gate that does not forward it fails every playback.
        control.onTracksSelected(params(0.seconds), TrackGroupArray.EMPTY, arrayOf())
        control.onStopped(PlayerId.UNSET)
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun `a metered network resumes loading only below nine minutes`() {
        val control = BufferPolicy.loadControl { false }
        control.onPrepared(PlayerId.UNSET)

        assertThat(control.shouldContinueLoading(params(9.minutes + 59.seconds))).isTrue()
        assertThat(control.shouldContinueLoading(params(10.minutes))).isFalse()
        assertThat(control.shouldContinueLoading(params(9.minutes + 30.seconds))).isFalse()
        assertThat(control.shouldContinueLoading(params(8.minutes + 59.seconds))).isTrue()
        assertThat(control.shouldContinueLoading(params(9.minutes + 30.seconds))).isTrue()
        assertThat(control.shouldContinueLoading(params(10.minutes))).isFalse()
    }

    @Test
    fun `time, not size, sets the target, so loading goes on past the default byte cap`() {
        val control = BufferPolicy.loadControl { true }
        control.onPrepared(PlayerId.UNSET)
        val allocator = control.getAllocator(PlayerId.UNSET)
        repeat(SegmentsPastTheCap) { allocator.allocate() }

        assertThat(control.shouldContinueLoading(params(15.minutes))).isTrue()
    }

    private companion object {
        /** 64 KB segments: about 26 MB, twice the default cap of the audio byte target. */
        const val SegmentsPastTheCap = 400
    }
}
