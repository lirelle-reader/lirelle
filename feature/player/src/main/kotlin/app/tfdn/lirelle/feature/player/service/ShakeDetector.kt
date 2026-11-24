package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import app.tfdn.lirelle.core.library.MonotonicClock
import kotlin.math.sqrt

/**
 * A shake of the phone, from the accelerometer: [joltsNeeded] jolts of more than [thresholdG] times gravity
 * within [windowMs]. The readings of one jolt count once (they are [joltGapMs] apart at least), and the jolts
 * right after a shake belong to it: another needs [windowMs] without one. A knock alone is not a shake.
 */
class ShakeDetector(
    private val clock: MonotonicClock,
    private val thresholdG: Float = DefaultThresholdG,
    private val joltsNeeded: Int = DefaultJolts,
    private val windowMs: Long = DefaultWindowMs,
    private val onShake: () -> Unit,
) {
    private val jolts = ArrayDeque<Long>()
    private var lastJolt: Long? = null
    private var shookAt: Long? = null

    /** One reading of the accelerometer, in m/s² (gravity included). */
    fun onAcceleration(x: Float, y: Float, z: Float) {
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < thresholdG) return
        val now = clock.nowMillis()
        val previous = lastJolt
        if (previous != null && now - previous < joltGapMs) return
        lastJolt = now
        if (shookAt?.let { now - it < windowMs } == true) {
            shookAt = now
            return
        }
        jolts.addLast(now)
        while (now - jolts.first() > windowMs) jolts.removeFirst()
        if (jolts.size >= joltsNeeded) {
            jolts.clear()
            shookAt = now
            onShake()
        }
    }

    companion object {
        const val DefaultThresholdG = 2.5f
        const val DefaultJolts = 2
        const val DefaultWindowMs = 1_000L
        private const val joltGapMs = 150L

        /** Shake to extend: during the sleep timer's last minute or fade, when the setting is on, while playing. */
        fun shouldListen(enabled: Boolean, lastMinute: Boolean, playing: Boolean) = enabled && lastMinute && playing
    }
}

/**
 * The accelerometer while [active], feeding a [ShakeDetector] whose shakes run [onShake] on the main thread
 * after a short vibration.
 */
class ShakeSensor(context: Context, clock: MonotonicClock, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val main = Handler(Looper.getMainLooper())
    private val detector = ShakeDetector(clock) { main.post(::shaken) }

    var active = false
        set(value) {
            if (field == value) return
            field = value
            val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            if (value) {
                sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
            } else {
                sensors.unregisterListener(this)
            }
        }

    override fun onSensorChanged(event: SensorEvent) {
        detector.onAcceleration(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun shaken() {
        if (!active) return
        // Two firm pulses: a single tick is lost in the hand's own shaking. Media usage, so a silent
        // ringer that mutes touch feedback does not swallow it.
        vibrator?.vibrate(Extended, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        onShake()
    }
}

/** On, off, on: two pulses of [PulseMs], [GapMs] apart, at full strength. */
private const val PulseMs = 120L
private const val GapMs = 90L
private const val FullStrength = 255
private val Extended: VibrationEffect = VibrationEffect.createWaveform(
    longArrayOf(0L, PulseMs, GapMs, PulseMs),
    intArrayOf(0, FullStrength, 0, FullStrength),
    -1,
)
