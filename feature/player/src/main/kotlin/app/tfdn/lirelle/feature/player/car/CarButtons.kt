@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.content.res.Resources
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import app.tfdn.lirelle.core.ui.R as UiR
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import java.text.NumberFormat
import kotlin.math.abs

/** A speed this close to the one an icon shows is drawn with it: 1.25 as 1.2, 1.75 as 1.8. */
private const val SameIcon = 0.06f

private const val MillisPerSecond = 1_000L
private const val SecondsPerMinute = 60L
private const val MinutesPerHour = 60L

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

/** The words of the car, in the device's language: those of the speed button, and of what it browses. */
class CarTexts(private val resources: Resources) {
    /** "Speed: 1.25×": the number as the language writes it, without a needless zero. */
    fun speed(speed: Float): String {
        val number = NumberFormat.getNumberInstance(resources.configuration.locales[0])
            .apply { maximumFractionDigits = 2 }
            .format(speed.toDouble())
        return resources.getString(R.string.player_button_speed, number)
    }

    fun tab(tab: CarPlace.Tab): String = resources.getString(
        when (tab) {
            CarPlace.Tab.Home -> R.string.car_tab_home
            CarPlace.Tab.Downloaded -> R.string.car_tab_downloaded
            CarPlace.Tab.Audiobooks -> R.string.car_tab_audiobooks
            CarPlace.Tab.Series -> R.string.car_tab_series
        },
    )

    /** The title of a group of the Home tab: the name of the phone's Home row. */
    fun section(section: CarSection): String = resources.getString(
        when (section) {
            CarSection.Continue -> R.string.car_section_continue
            CarSection.UpNext -> R.string.car_section_up_next
            CarSection.RecentlyAdded -> R.string.car_section_recently_added
        },
    )

    /** The line under a book's title: "9h 12m left", "A Saga #2", or its authors. */
    fun line(line: CarLine): String = when (line) {
        is CarLine.Left -> resources.getString(R.string.car_line_left, timeLeft(line.millis / MillisPerSecond))
        // A series and a number, as the phone's lists write them.
        is CarLine.InSeries ->
            line.index?.let { resources.getString(UiR.string.book_series_volume, line.name, it) } ?: line.name
        is CarLine.Author -> line.name
    }

    /** "3 books": how many books of a series can be listened to. */
    fun seriesBooks(count: Int): String = resources.getQuantityString(R.plurals.car_series_books, count, count)

    /** Why a place lists nothing, as a sentence. */
    fun notice(notice: CarNotice): String = resources.getString(
        when (notice) {
            CarNotice.NothingToListen -> R.string.car_notice_nothing
            CarNotice.SignInNeeded -> R.string.car_notice_sign_in
            CarNotice.Unreachable -> R.string.car_notice_unreachable
        },
    )

    /** Nothing can be listened to for the words [query] a car was told. */
    fun searchNothing(query: String): String = resources.getString(R.string.car_search_nothing, query)

    fun nothingToResume(): String = resources.getString(R.string.car_nothing_to_resume)

    /** A book could not be loaded: the words of the phone's player. */
    fun loadFailed(): String = resources.getString(R.string.player_load_failed)

    /**
     * "9h 12m", "40 min": the time left in short, in the words of the phone's Home (its formatter is a
     * composable, which a service cannot call: the strings are the same).
     */
    private fun timeLeft(seconds: Long): String {
        val minutes = seconds / SecondsPerMinute
        val hours = minutes / MinutesPerHour
        return if (hours > 0) {
            resources.getString(UiR.string.continue_time_left_hours, hours, minutes % MinutesPerHour)
        } else {
            resources.getString(UiR.string.continue_time_left_minutes, minutes)
        }
    }
}

/**
 * The speed button, made for the car: it names the current [speed], and a tap goes to the next one
 * ([PlayerCommand.NextSpeed]). The car has no room for a sheet of speeds, nor the driver the time. In the
 * overflow: after the other buttons, wherever there is room for it.
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
