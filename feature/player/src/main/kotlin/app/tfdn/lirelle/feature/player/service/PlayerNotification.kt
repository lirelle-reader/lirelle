package app.tfdn.lirelle.feature.player.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.SLOT_FORWARD
import androidx.media3.session.CommandButton.SLOT_OVERFLOW
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.feature.player.R
import kotlin.math.ceil

private const val MillisPerMinute = 60_000.0

private const val Tag = "PlaybackService"

/**
 * The system refused to start playback from the background (Android 12 and later): nothing plays without a
 * notification. A tap on a plain one opens the app ([open]: the reader for a narration), when notifications
 * are allowed.
 */
internal fun offerToContinue(context: Context, title: String, open: PendingIntent?) {
    Log.w(Tag, "Playback could not start in the foreground from the background")
    val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    NotificationManagerCompat.from(context)
        .notify(PlayerNotification.ContinueId, PlayerNotification.continueNotification(context, title, open))
}

/** The words of the system controls, in the device's language. */
class PlayerTexts(private val resources: Resources) {
    /** The words of a narration's notification. */
    val narration = NarrationTexts(resources)

    fun back(seconds: Int): String = resources.getString(R.string.player_button_back, seconds)

    fun forward(seconds: Int): String = resources.getString(R.string.player_button_forward, seconds)

    fun previousChapter(): String = resources.getString(R.string.player_button_previous_chapter)

    fun nextChapter(): String = resources.getString(R.string.player_button_next_chapter)

    fun listenToNext(): String = resources.getString(R.string.player_button_listen_next)

    fun finished(title: String): String = resources.getString(R.string.player_notification_finished, title)

    fun nextAvailable(): String = resources.getString(R.string.player_notification_next_available)

    /** "Stops in 23 min" (rounded up), or "Stops at the end of the chapter". */
    fun sleep(state: SleepState): String = when (state.choice) {
        SleepChoice.EndOfChapter -> resources.getString(R.string.player_notification_sleep_chapter)
        is SleepChoice.Minutes -> resources.getString(
            R.string.player_notification_sleep_minutes,
            ceil((state.remainingMs ?: 0) / MillisPerMinute).toInt().coerceAtLeast(1),
        )
    }

    fun withSleep(chapter: String, sleep: String): String =
        resources.getString(R.string.player_notification_with_sleep, chapter, sleep)

    fun part(number: Int): String = resources.getString(R.string.player_part, number)
}

/**
 * The media notification (and the lock screen, the watch…, which read the same session): the cover, the
 * book's title and the chapter's; five buttons, previous chapter, back, play/pause, forward, next chapter,
 * of which back, play/pause and forward in the compact view; the sleep timer's time left after the chapter's
 * title; "*Title* — finished" with "Listen to the next one" at the end of the book.
 *
 * A narration shows its section's title ("Section 12" for one without), and at its end the book's title over
 * "Narration finished", without buttons.
 *
 * Play/pause is the player's own button. Back and forward take the slots next to it, so they are also the
 * compact view's; the chapter buttons go to the overflow, after them.
 */
@OptIn(UnstableApi::class)
object PlayerNotification {
    private const val ChannelId = "playback"

    /** The session's media button preferences for this moment. */
    fun buttons(
        texts: PlayerTexts,
        settings: PlayerSettings,
        finished: FinishedBook?,
        narrationFinished: Boolean = false,
    ): List<CommandButton> {
        if (narrationFinished) return emptyList()
        if (finished != null) {
            if (finished.nextVolume == null) return emptyList()
            return listOf(
                button(CommandButton.ICON_NEXT, PlayerCommand.ListenToNext, texts.listenToNext(), SLOT_FORWARD),
            )
        }
        val back = settings.backSeconds
        val forward = settings.forwardSeconds
        return listOf(
            button(CommandButton.ICON_PREVIOUS, PlayerCommand.PreviousChapter, texts.previousChapter(), SLOT_OVERFLOW),
            button(backIcon(back), PlayerCommand.Back, texts.back(back), CommandButton.SLOT_BACK),
            button(forwardIcon(forward), PlayerCommand.Forward, texts.forward(forward), SLOT_FORWARD),
            button(CommandButton.ICON_NEXT, PlayerCommand.NextChapter, texts.nextChapter(), SLOT_OVERFLOW),
        )
    }

    /**
     * What the current chapter shows instead of the book's and the chapter's titles. [chapterTitle] is the one
     * to show: for a narration, its section's, named when it has none.
     */
    fun override(texts: PlayerTexts, extras: PlayerSessionExtras, chapterTitle: String?): ChapterPlayer.Override {
        val finished = extras.finished
        val sleep = extras.sleep
        return when {
            finished != null -> ChapterPlayer.Override(
                title = texts.finished(extras.book?.title.orEmpty()),
                artist = if (finished.nextVolume != null) texts.nextAvailable() else null,
            )
            extras.narrationFinished -> ChapterPlayer.Override(artist = texts.narration.finished())
            sleep != null -> {
                ChapterPlayer.Override(artist = texts.withSleep(chapterTitle.orEmpty(), texts.sleep(sleep)))
            }
            // A narration's section may have no title of its own: the one named for it.
            else -> ChapterPlayer.Override(artist = chapterTitle.takeIf { extras.narrationFileId != null })
        }
    }

    /** The notification's id when playback could not start from the background. */
    const val ContinueId = 1002

    /**
     * Shown when the system refused to start playback from the background (the next volume, a button): a tap
     * opens the app, where play works.
     */
    fun continueNotification(context: Context, title: String, open: PendingIntent?): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_player_notification)
            .setContentTitle(context.getString(R.string.player_notification_continue))
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    /** Playback really started: [continueNotification] has nothing left to offer. */
    fun dismissContinue(context: Context) = NotificationManagerCompat.from(context).cancel(ContinueId)

    /** The instant notification of a foreground entered only to be left ([ForegroundDebt]). */
    fun startingNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.drawable.ic_player_notification)
            .setContentTitle(context.getString(R.string.player_notification_channel))
            .setSilent(true)
            .build()
    }

    private fun ensureChannel(context: Context) = NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(ChannelId, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.player_notification_channel))
            .build(),
    )

    /** Media3's notification, with the app's icon and channel name. */
    fun provider(context: Context): MediaNotification.Provider =
        DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(ChannelId)
            .setChannelName(R.string.player_notification_channel)
            .build()
            .apply { setSmallIcon(R.drawable.ic_player_notification) }

    private fun button(icon: Int, command: PlayerCommand, name: String, slot: Int): CommandButton =
        CommandButton.Builder(icon)
            .setSessionCommand(command.sessionCommand)
            .setDisplayName(name)
            .setSlots(slot)
            .build()

    private fun backIcon(seconds: Int): Int = when (seconds) {
        BackIcons.FIVE -> CommandButton.ICON_SKIP_BACK_5
        BackIcons.TEN -> CommandButton.ICON_SKIP_BACK_10
        BackIcons.FIFTEEN -> CommandButton.ICON_SKIP_BACK_15
        BackIcons.THIRTY -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    private fun forwardIcon(seconds: Int): Int = when (seconds) {
        BackIcons.FIVE -> CommandButton.ICON_SKIP_FORWARD_5
        BackIcons.TEN -> CommandButton.ICON_SKIP_FORWARD_10
        BackIcons.FIFTEEN -> CommandButton.ICON_SKIP_FORWARD_15
        BackIcons.THIRTY -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    /** The intervals Media3 draws an icon of its own for. */
    private object BackIcons {
        const val FIVE = 5
        const val TEN = 10
        const val FIFTEEN = 15
        const val THIRTY = 30
    }
}
