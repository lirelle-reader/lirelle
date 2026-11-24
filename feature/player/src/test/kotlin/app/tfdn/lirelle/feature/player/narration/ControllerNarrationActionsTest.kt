package app.tfdn.lirelle.feature.player.narration

import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.feature.player.controller.PlayerHarness
import app.tfdn.lirelle.feature.player.controller.SessionNarrations
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The narration's controls, as the player controller carries them out. */
class ControllerNarrationActionsTest {

    @Test
    fun `each control is the player's command for it`() = runTest {
        val harness = PlayerHarness(this)
        harness.show(SessionNarrations.at())
        val actions = harness.controller.narrationActions()

        actions.playPause()
        actions.previousSentence()
        actions.nextSentence()
        actions.back()
        actions.forward()
        actions.seekInSection(0.5f)
        actions.goToSection(0)
        actions.setSpeed(1.25f)
        actions.startSleep(SleepChoice.Minutes(15))
        actions.cancelSleep()
        actions.stop()

        assertThat(harness.connection.sent).containsExactly(
            "playPause",
            PlayerCommand.PreviousSentence,
            PlayerCommand.NextSentence,
            PlayerCommand.Back,
            PlayerCommand.Forward,
            // Half of the second section, 10 s long.
            "seekTo 1 5000",
            "seekTo 0 0",
            PlayerCommand.SetSpeed(1.25f),
            PlayerCommand.StartSleep(SleepChoice.Minutes(15)),
            PlayerCommand.CancelSleep,
            PlayerCommand.CloseBook,
        ).inOrder()
    }

    @Test
    fun `shaking to extend the timer is the player's setting`() = runTest {
        val harness = PlayerHarness(this)

        harness.controller.narrationActions().setShakeToExtend(false)

        assertThat(harness.settings.settings.value.shakeToExtend).isFalse()
    }
}
