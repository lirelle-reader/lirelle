package app.tfdn.lirelle.feature.player

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.player.service.InMemoryPlayerSettingsStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each player setting shows what is stored and stores what is chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerSettingsScreenTest {

    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val store = InMemoryPlayerSettingsStore()
    private var backs = 0

    private fun show(settings: PlayerSettings = PlayerSettings()) {
        store.settings.value = settings
        val viewModel = PlayerSettingsViewModel(store, CoroutineScope(Dispatchers.Main))
        compose.setContent {
            LirelleTheme {
                PlayerSettingsScreen(viewModel = viewModel, onBack = { backs++ })
            }
        }
    }

    private fun choice(group: String, seconds: Int) =
        compose.onNodeWithTag(skipChoiceTag(group, seconds)).performScrollTo()

    @Test
    fun `the stored back interval is chosen, and another is stored`() {
        show()

        choice(BackGroup, 15).assertIsSelected()
        choice(BackGroup, 30).assertIsNotSelected().performClick()

        compose.waitForIdle()
        assertThat(store.settings.value.backSeconds).isEqualTo(30)
        choice(BackGroup, 30).assertIsSelected()
        choice(BackGroup, 15).assertIsNotSelected()
    }

    @Test
    fun `the stored forward interval is chosen, and another is stored`() {
        show()

        choice(ForwardGroup, 30).assertIsSelected()
        choice(ForwardGroup, 5).performClick()

        compose.waitForIdle()
        assertThat(store.settings.value.forwardSeconds).isEqualTo(5)
        assertThat(store.settings.value.backSeconds).isEqualTo(15)
    }

    @Test
    fun `every interval from 5 to 60 seconds is offered, in seconds a screen reader says in full`() {
        show()

        listOf(5, 10, 15, 30, 45, 60).forEach { seconds ->
            choice(BackGroup, seconds).assertIsDisplayed()
            choice(ForwardGroup, seconds).assertIsDisplayed()
        }
        compose.onNodeWithText("Skip back").assertIsDisplayed()
        compose.onNodeWithText("Skip forward").performScrollTo().assertIsDisplayed()
        choice(BackGroup, 45).assert(hasContentDescription("45 seconds"))
    }

    @Test
    fun `rewind on resume is turned off and on`() {
        show()

        switch("Rewind on resume").assertIsOn().performClick()
        compose.waitForIdle()
        assertThat(store.settings.value.rewindOnResume).isFalse()
        switch("Rewind on resume").assertIsOff().performClick()
        compose.waitForIdle()
        assertThat(store.settings.value.rewindOnResume).isTrue()
    }

    @Test
    fun `the sleep fade is turned off`() {
        show()

        switch("Fade out before stopping").assertIsOn().performClick()

        compose.waitForIdle()
        assertThat(store.settings.value.sleepFade).isFalse()
        assertThat(store.settings.value.rewindOnResume).isTrue()
    }

    @Test
    fun `shake to extend, stored off, is turned on`() {
        show(PlayerSettings(shakeToExtend = false))

        switch("Shake to extend").assertIsOff().performClick()

        compose.waitForIdle()
        assertThat(store.settings.value.shakeToExtend).isTrue()
    }

    @Test
    fun `back leaves the screen`() {
        show()

        compose.onNodeWithText("Audio player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(backs).isEqualTo(1)
    }

    private fun switch(label: String) = compose.onNodeWithText(label).performScrollTo()

    private companion object {
        const val BackGroup = "back"
        const val ForwardGroup = "forward"
    }
}
