package app.tfdn.lirelle

import androidx.compose.ui.test.junit4.createComposeRule
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
 * The message of an update that could not finish is whole, breaks between words only and is cut by nothing,
 * on a narrow phone, a phone and a tablet, in both languages, at the default and the largest text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class UpdateUnfinishedTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `the message fits, alone on the screen, and is not cut short`() {
        compose.setContent {
            LirelleTheme {
                LirelleLevels(
                    shown = ShownSession(SessionState.Loading, profileId = null, updateUnfinished = true),
                    signIn = {},
                    signedIn = {},
                )
            }
        }

        val texts = compose.assertAllTextFits()

        val message = ApplicationProvider.getApplicationContext<LirelleApplication>()
            .getString(R.string.update_unfinished)
        assertThat(texts.map { it.text }).containsExactly(message)
        assertThat(texts.single().isWhollyShown).isTrue()
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
