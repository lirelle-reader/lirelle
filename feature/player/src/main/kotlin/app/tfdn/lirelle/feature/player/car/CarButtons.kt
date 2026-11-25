@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import java.text.NumberFormat
import kotlin.math.abs

/** A speed this close to the one an icon shows is drawn with it: 1.25 as 1.2, 1.75 as 1.8. */
private const val SameIcon = 0.06f

/** The speeds Media3 draws an icon of its own for. */
private val SpeedIcons = listOf(
    0.5f to CommandButton.ICON_PLAYBACK_SPEED_0_5,
    0.8f to CommandButton.ICON_PLAYBACK_SPEED_0_8,
    1f to CommandButton.ICON_PLAYBACK_SPEED_1_0,
    1.2f to CommandButton.ICON_PLAYBACK_SPEED_1_2,
    1.5f to CommandButton.ICON_PLAYBACK_SPEED_1_5,
    1.8f to CommandButton.ICON_PLAYBACK_SPEED_1_8,
    2f to CommandButton.ICON_PLAYBACK_SPEED_2_0,
)

/** The words of the car's own buttons, in the device's language. */
class CarTexts(private val resources: Resources) {
    /** "Speed: 1.25×": the number as the language writes it, without a needless zero. */
    fun speed(speed: Float): String {
        val number = NumberFormat.getNumberInstance(resources.configuration.locales[0])
            .apply { maximumFractionDigits = 2 }
            .format(speed.toDouble())
        return resources.getString(R.string.player_button_speed, number)
    }
}

/**
 * The car's speed button: it names the current [speed], and a tap goes to the next one
 * ([PlayerCommand.NextSpeed]). The car has no room for a sheet of speeds, nor the driver the time.
 */
internal fun speedButton(texts: CarTexts, speed: Float): CommandButton =
    CommandButton.Builder(speedIcon(speed))
        .setSessionCommand(PlayerCommand.NextSpeed.sessionCommand)
        .setDisplayName(texts.speed(speed))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

/** The icon of the nearest speed Media3 draws; the plain speed icon when none is near. */
private fun speedIcon(speed: Float): Int =
    SpeedIcons.minBy { abs(it.first - speed) }.takeIf { abs(it.first - speed) <= SameIcon }?.second
        ?: CommandButton.ICON_PLAYBACK_SPEED
