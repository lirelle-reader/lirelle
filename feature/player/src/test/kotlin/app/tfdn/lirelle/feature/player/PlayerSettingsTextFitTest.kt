package app.tfdn.lirelle.feature.player

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the player settings overflows, is clipped or breaks mid-word, on a narrow phone, a phone and a
 * tablet, in both languages, at the default and the largest text size, as a screen with its back arrow and as
 * a page in a pane. The title is whole in both.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class PlayerSettingsTextFitTest(
    language: String,
    width: Int,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val nothing = object : PlayerSettingsChanges {
        override fun setBackSeconds(seconds: Int) = Unit

        override fun setForwardSeconds(seconds: Int) = Unit

        override fun setRewindOnResume(enabled: Boolean) = Unit

        override fun setSleepFade(enabled: Boolean) = Unit

        override fun setShakeToExtend(enabled: Boolean) = Unit
    }

    private fun show(onBack: (() -> Unit)?) {
        compose.setContent {
            LirelleTheme { PlayerSettingsContent(PlayerSettings(), nothing, onBack) }
        }
    }

    /**
     * The title as it was laid out. It is asked for itself: a text held to one line that would need two is
     * neither ended with an ellipsis nor drawn past its edges, its second line is simply not there.
     */
    private fun titleLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(context.getString(R.string.player_settings_title))
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single()
    }

    private fun assertTitleWhole() {
        val layout = titleLayout()
        val title = layout.layoutInput.text.text
        assertThat(layout.lineCount).isEqualTo(1)
        assertWithMessage("what is drawn of \"$title\"")
            .that(title.substring(0, layout.getLineEnd(0, visibleEnd = true)))
            .isEqualTo(title)
        assertThat(layout.getLineRight(0) - layout.getLineLeft(0)).isAtMost(layout.size.width + 1f)    }

    @Test
    fun `the screen fits and its title is whole beside the arrow`() {
        show(onBack = {})

        compose.assertAllTextFits()

        assertTitleWhole()
        compose.onNodeWithContentDescription(context.getString(R.string.player_settings_back)).assertExists()
    }

    @Test
    fun `the page fits in a pane and its title is whole`() {
        show(onBack = null)

        compose.assertAllTextFits()

        assertTitleWhole()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1} dp, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("en", "fr").flatMap { language ->
                listOf(320, 411, 1280).flatMap { width ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(language, width, fontScale) }
                }
            }
    }
}
