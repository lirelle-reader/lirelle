package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * What a screen without its server says while the profile waits for a sign-in, and the bar of a reload that
 * failed then: no text is cut — the sentence and both buttons wrap between words — and every action can be
 * reached. A long username is the worst case of the sentence.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SignInNeededFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Of the kind with the longest name, whose sign-in expires as a session: the longest sentence. */
    private val waiting = SignInNeeded("p0", "marie-christine.delacroix-de-la-fontaine", ServerKind.Audiobookshelf)

    private fun text(id: Int) = compose.activity.getString(id)

    /** A username has nowhere to break: the sentence that holds a long one may break inside it, never be cut. */
    private val sentence
        get() = compose.activity.getString(
            R.string.sign_in_needed_message,
            "marie-christine.delacroix-de-la-fontaine · Audiobookshelf",
        )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalOpenDownloads provides {},
                    LocalSignInNeeded provides waiting,
                    LocalSignInAgain provides {},
                    content = content,
                )
            }
        }
    }

    @Test
    fun `the screen's texts all fit and both actions can be reached`() {
        show { NoNetworkState(onRetry = {}, onOpenDownloads = {}) }

        val signIn = text(R.string.sign_in_again)
        val downloads = text(R.string.no_network_open_downloads)
        val texts = compose.assertAllTextFits(unbreakable = setOf(sentence), wrappingLabels = setOf(signIn, downloads))

        // The title, the sentence and the two buttons: none cut short.
        assertThat(texts).hasSize(4)
        assertThat(texts.filter { it.isEllipsized }).isEmpty()
        compose.onNodeWithTag(SignInNeededAgainTag).performScrollTo().assertIsWhollyShown()
        compose.onNodeWithTag(SignInNeededDownloadsTag).performScrollTo().assertIsWhollyShown()
    }

    @Test
    fun `the bar's texts all fit and its action can be reached`() {
        show { Column { ServerFailureBar(ApiError.ServerUnreachable, onRetry = {}) } }

        val texts = compose.assertAllTextFits(
            unbreakable = setOf(sentence),
            wrappingLabels = setOf(text(R.string.sign_in_again)),
        )

        // The title, the sentence and the button: none cut short.
        assertThat(texts).hasSize(3)
        assertThat(texts.filter { it.isEllipsized }).isEmpty()
        compose.onNodeWithTag(SignInNeededBarTag).assertIsWhollyShown()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf("w320dp-h640dp", "w411dp-h891dp", "w1280dp-h800dp").flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
