package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import app.tfdn.lirelle.core.audio.settings.PlayerSettings

private const val SecondMs = 1_000L

/**
 * Tells the session's controllers what changed: the extras ([PlayerSessionExtras], the sleep timer's time
 * left to the second), the media buttons (the intervals, the end of the book), and what the current chapter
 * shows in the notification (the time left, "finished"). Only what changed is sent. Call it on the main thread.
 *
 * A car has buttons of its own, which name the [speed]: [setButtons] sends a list to every controller,
 * [setCarButtons] the car's list to the cars. What goes to every controller reaches the cars too, so the car's
 * list is sent again after it.
 */
@OptIn(UnstableApi::class)
class SessionPublisher(
    private val texts: PlayerTexts,
    private val chapters: ChapterPlayer,
    private val navigator: BookNavigator,
    private val setExtras: (Bundle) -> Unit,
    private val setButtons: (List<CommandButton>) -> Unit,
    private val setCarButtons: (List<CommandButton>) -> Unit,
) {
    private var extras = PlayerSessionExtras()
    private var sentExtras: PlayerSessionExtras? = null
    private var sentButtons: List<CommandButton>? = null
    private var sentCarButtons: List<CommandButton>? = null

    var settings = PlayerSettings()
        set(value) {
            field = value
            chapters.skips = ChapterPlayer.Skips(value.backSeconds * SecondMs, value.forwardSeconds * SecondMs)
            publishButtons()
        }

    /** The speed playback has now: the car's speed button names it. */
    var speed = 1f
        set(value) {
            field = value
            publishButtons()
        }

    fun publish(next: PlayerSessionExtras) {
        extras = next.copy(sleep = next.sleep?.let { it.copy(remainingMs = it.remainingMs?.roundedUpToSecond()) })
        if (extras != sentExtras) {
            sentExtras = extras
            setExtras(extras.toBundle())
        }
        publishButtons()
        refresh()
    }

    /** Playback may be in another chapter: the session and the notification follow. */
    fun refresh() {
        val playlist = navigator.playlist
        val index = navigator.chapterIndex()
        val chapterTitle = if (extras.narrationFileId != null) {
            playlist?.narrationChapterTitle(index, texts.narration)
        } else {
            playlist?.chapters?.getOrNull(index)?.title
        }
        chapters.override = PlayerNotification.override(texts, extras, chapterTitle)
        chapters.refresh()
    }

    /** The media buttons for this moment, for a controller that connects: the car's own for a [car]. */
    fun buttons(car: Boolean = false): List<CommandButton> =
        PlayerNotification.buttons(texts, settings, extras.finished, extras.narrationFinished, speed.takeIf { car })

    private fun publishButtons() {
        val buttons = buttons()
        val carButtons = buttons(car = true)
        val changed = buttons != sentButtons
        if (changed) {
            sentButtons = buttons
            setButtons(buttons)
        }
        if (changed || carButtons != sentCarButtons) {
            sentCarButtons = carButtons
            setCarButtons(carButtons)
        }
    }
}

private fun Long.roundedUpToSecond(): Long = (this + SecondMs - 1) / SecondMs * SecondMs
