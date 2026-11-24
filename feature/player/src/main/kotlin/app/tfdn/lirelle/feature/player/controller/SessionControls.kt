package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.feature.player.PlaybackControls
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import kotlin.math.roundToLong

/** The player's controls as the session's commands. */
internal class SessionControls(private val connection: PlayerConnection) : PlaybackControls {

    override fun playPause() = connection.playPause()

    override fun back() = connection.send(PlayerCommand.Back)

    override fun forward() = connection.send(PlayerCommand.Forward)

    override fun previousChapter() = connection.send(PlayerCommand.PreviousChapter)

    override fun nextChapter() = connection.send(PlayerCommand.NextChapter)

    override fun seekInChapter(fraction: Float) {
        val now = connection.snapshot.value
        val duration = now.chapter?.durationMs ?: return
        connection.seekTo(now.chapterIndex, (duration * fraction.coerceIn(0f, 1f).toDouble()).roundToLong())
    }

    override fun setSpeed(speed: Float) = connection.send(PlayerCommand.SetSpeed(speed))

    override fun startSleep(choice: SleepChoice) = connection.send(PlayerCommand.StartSleep(choice))

    override fun cancelSleep() = connection.send(PlayerCommand.CancelSleep)

    override fun stopListening() = connection.send(PlayerCommand.CloseBook)
}
