package app.tfdn.lirelle.feature.player.service

import android.hardware.SensorManager
import app.tfdn.lirelle.core.library.MonotonicClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShakeDetectorTest {

    private var now = 0L
    private var shakes = 0
    private val detector = ShakeDetector(MonotonicClock { now }) { shakes++ }
    private val g = SensorManager.GRAVITY_EARTH

    @Test
    fun `the phone at rest or moved gently is no shake`() {
        detector.onAcceleration(0f, 0f, g)
        detector.onAcceleration(g, g, g)

        assertThat(shakes).isEqualTo(0)
    }

    @Test
    fun `one jolt alone is a knock, not a shake`() {
        detector.onAcceleration(3 * g, 0f, g)
        now += 2_000
        detector.onAcceleration(-3 * g, 0f, g)

        assertThat(shakes).isEqualTo(0)
    }

    @Test
    fun `two jolts within a second are a shake, and the jolts right after it are the same shake`() {
        detector.onAcceleration(3 * g, 0f, g)
        now += 300
        detector.onAcceleration(-3 * g, 0f, g)
        assertThat(shakes).isEqualTo(1)

        now += 300
        detector.onAcceleration(3 * g, 0f, g)
        now += 300
        detector.onAcceleration(-3 * g, 0f, g)
        assertThat(shakes).isEqualTo(1)

        now += 2_000
        detector.onAcceleration(3 * g, 0f, g)
        now += 300
        detector.onAcceleration(-3 * g, 0f, g)
        assertThat(shakes).isEqualTo(2)
    }

    @Test
    fun `the readings of one jolt count once`() {
        detector.onAcceleration(3 * g, 0f, g)
        now += 20
        detector.onAcceleration(3.2f * g, 0f, g)
        now += 20
        detector.onAcceleration(3.1f * g, 0f, g)

        assertThat(shakes).isEqualTo(0)
    }

    @Test
    fun `it listens only during the last minute or the fade, with the setting on, while playing`() {
        assertThat(ShakeDetector.shouldListen(enabled = true, lastMinute = true, playing = true)).isTrue()
        assertThat(ShakeDetector.shouldListen(enabled = false, lastMinute = true, playing = true)).isFalse()
        assertThat(ShakeDetector.shouldListen(enabled = true, lastMinute = false, playing = true)).isFalse()
        assertThat(ShakeDetector.shouldListen(enabled = true, lastMinute = true, playing = false)).isFalse()
    }
}
