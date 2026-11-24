package app.tfdn.lirelle.core.audio.settings

import app.tfdn.lirelle.core.audio.timer.SleepChoice
import kotlin.math.roundToInt

const val MinSkipSeconds = 5
const val MaxSkipSeconds = 60
const val MinSpeed = 0.5f
const val MaxSpeed = 3f
const val SpeedStep = 0.05f
private const val StepsPerUnit = 20

data class PlayerSettings(
    val backSeconds: Int = 15,
    val forwardSeconds: Int = 30,
    val rewindOnResume: Boolean = true,
    val sleepFade: Boolean = true,
    val shakeToExtend: Boolean = true,
    val lastSleepChoice: SleepChoice? = null,
    /** The speed a book without one of its own starts at. */
    val lastSpeed: Float = 1f,
) {
    /** The same, with the skips from 5 to 60 seconds and the speed from 0.5 to 3 by 0.05. */
    fun normalized(): PlayerSettings = copy(
        backSeconds = backSeconds.coerceIn(MinSkipSeconds, MaxSkipSeconds),
        forwardSeconds = forwardSeconds.coerceIn(MinSkipSeconds, MaxSkipSeconds),
        lastSpeed = lastSpeed.toValidSpeed(),
    )
}

/** Within 0.5 to 3, on a step of 0.05. */
fun Float.toValidSpeed(): Float =
    ((if (isNaN()) 1f else this).coerceIn(MinSpeed, MaxSpeed) * StepsPerUnit).roundToInt() / StepsPerUnit.toFloat()
