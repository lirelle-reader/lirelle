package app.tfdn.lirelle

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.profile.ShownSession
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * The wait of a change of profile says what is waited for, whole, breaking between words only and cut by
 * nothing, on a narrow phone, a phone and a tablet, in both languages, at the default and the largest text
 * size; and a screen reader is told of it without being interrupted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ChangingProfileTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Read when a test asks for it: the device's language is set by its rule, after this class is made. */
    private val text: String
        get() = ApplicationProvider.getApplicationContext<LirelleApplication>().getString(R.string.profile_changing)

    private fun show() = compose.setContent {
        LirelleTheme {
            LirelleLevels(
                shown = ShownSession(SessionState.Loading, profileId = null, switching = true),
                signIn = {},
                signedIn = {},
            )
        }
    }

    @Test
    fun `the wait's text fits, alone on the screen, and is not cut short`() {
        show()

        val texts = compose.assertAllTextFits()

        assertThat(texts.map { it.text }).containsExactly(text)
        assertThat(texts.single().isWhollyShown).isTrue()
    }

    @Test
    fun `the wait's text is announced politely to a screen reader`() {
        show()

        compose.onNodeWithText(text)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w411dp-h891dp-420dpi", "w1280dp-h800dp-xhdpi").flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
