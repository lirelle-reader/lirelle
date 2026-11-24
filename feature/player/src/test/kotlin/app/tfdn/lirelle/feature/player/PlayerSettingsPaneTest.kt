package app.tfdn.lirelle.feature.player

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** The player settings as a page in a pane, beside the list they were chosen from: no way back of their own. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerSettingsPaneTest {

    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val store = InMemoryPlayerSettingsStore()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun show(onBack: (() -> Unit)?) {
        val viewModel = PlayerSettingsViewModel(store, CoroutineScope(Dispatchers.Main))
        compose.setContent {
            LirelleTheme { PlayerSettingsScreen(viewModel = viewModel, onBack = onBack) }
        }
    }

    @Test
    fun `in a pane the page has its title and no arrow`() {
        show(onBack = null)

        compose.onNode(hasText("Audio player") and heading).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `in a pane the settings show and change as on the screen`() {
        show(onBack = null)

        compose.onNodeWithTag(skipChoiceTag("back", 15)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(skipChoiceTag("back", 45)).performScrollTo().performClick()

        compose.waitForIdle()
        assertThat(store.settings.value.backSeconds).isEqualTo(45)
    }

    /** Where the first group's name starts, in dp from the start of what is shown. */
    private fun firstLabelStart(): Float {
        val left = compose.onNode(hasText("Skip back") and heading).fetchSemanticsNode().boundsInRoot.left
        return left / compose.density.density
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `in a wide pane the settings start at the pane's start, as the other pages do`() {
        show(onBack = null)

        assertThat(firstLabelStart()).isWithin(0.5f).of(16f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on its own a wide screen keeps the settings in the middle`() {
        show(onBack = {})

        // 600 dp of settings in the middle of 1280 dp, then the gutter.
        assertThat(firstLabelStart()).isWithin(0.5f).of(356f)
    }

    @Test
    fun `on its own the screen keeps its arrow`() {
        var backs = 0
        show(onBack = { backs++ })

        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(backs).isEqualTo(1)
    }
}
