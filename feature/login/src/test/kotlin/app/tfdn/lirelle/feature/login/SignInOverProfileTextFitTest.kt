package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * What the sign-in screens add when they are shown over a profile fits on a narrow phone, a phone and a
 * tablet, in both languages, at the default and the largest text size: the headings and the line under "Add a
 * profile" are whole and break between words; the profile, its server's address and the bar's "Back to …"
 * stay on one line each, and end with an ellipsis when they are long.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SignInOverProfileTextFitTest(
    private val screen: String,
    language: String,
    private val fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Read when a test asks for it: the device's language is set by its rule, after this class is made. */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val longServer = "https://bibliotheque-numerique.intranet.example-organisation.org:8443"
    private val longUsername = "marie-charlotte.de-la-fontaine-des-grands-champs@example-organisation.org"
    private val camille = ProfileLabel("camille", ServerKind.BookOrbit, CamilleServer)
    private val long = ProfileLabel(longUsername, ServerKind.BookOrbit, longServer)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun line(profile: ProfileLabel) = "${profile.username} · BookOrbit"

    private fun backTo(profile: ProfileLabel) = text(R.string.sign_in_back_to_profile, line(profile))

    private fun state(server: String) = SignInUiState(
        serverUrl = server,
        isLoadingOptions = false,
        options = LoginOptions(
            passwordEnabled = true,
            oidcProviders = listOf(OidcProvider("authentik", "Authentik", "client-id-1", "openid")),
        ),
    )

    /** The bar over the sign-in step, as the screens over a profile lay them out. */
    private fun show(purpose: SignInPurpose, current: ProfileLabel, again: ProfileLabel? = null) {
        val server = again?.serverUrl ?: CamilleServer
        compose.setContent {
            LirelleTheme {
                Column {
                    BackToProfileBar(current = current, onBack = {})
                    SignInScreen(
                        state = state(server),
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onSignIn = {},
                        onOidc = {},
                        onChangeServer = {},
                        onRetry = {},
                        purpose = purpose,
                        again = again,
                    )
                }
            }
        }
    }

    private fun List<LaidOutText>.the(text: String) = single { it.text == text }

    private fun LaidOutText.assertWholeOnOneLine() {
        assertWithMessage("lines of $this").that(lineCount).isEqualTo(1)
        assertWithMessage("$this is cut short").that(isEllipsized).isFalse()
    }

    private fun LaidOutText.assertCutOnOneLine() {
        assertWithMessage("lines of $this").that(lineCount).isEqualTo(1)
        assertWithMessage("$this ends with an ellipsis").that(isEllipsized).isTrue()
    }

    @Test
    fun `adding a profile fits, its heading and the line under it whole`() {
        show(SignInPurpose.Add, current = camille)

        // Only the bar's words may be cut short, at the largest text size on the narrowest screen.
        val texts = compose.assertAllTextFits(unbreakable = setOf(backTo(camille), CamilleServer))

        assertThat(texts.the(text(R.string.add_profile_title)).isEllipsized).isFalse()
        assertThat(texts.the(text(R.string.add_profile_text)).isEllipsized).isFalse()
        assertThat(texts.the(backTo(camille)).lineCount).isEqualTo(1)
    }

    @Test
    fun `the bar names a short profile whole at the default text size`() {
        Assume.assumeTrue(fontScale == 1.0f)
        show(SignInPurpose.Add, current = camille)

        compose.assertAllTextFits(unbreakable = setOf(CamilleServer)).the(backTo(camille)).assertWholeOnOneLine()
    }

    @Test
    fun `the bar ends a long username with an ellipsis, on one line`() {
        // The bar is as wide as the screen: a tablet's shows it whole.
        Assume.assumeTrue(screen != Tablet)
        show(SignInPurpose.Add, current = long)

        val texts = compose.assertAllTextFits(unbreakable = setOf(backTo(long), CamilleServer))

        texts.the(backTo(long)).assertCutOnOneLine()
    }

    @Test
    fun `signing in again fits, with its heading whole and a short profile and address whole on a line each`() {
        Assume.assumeTrue(fontScale == 1.0f)
        show(SignInPurpose.Again("p0"), current = camille, again = camille)

        // Said unbreakable so that the lines below tell what is asked of them: one line, and whole.
        val texts = compose.assertAllTextFits(unbreakable = setOf(line(camille), CamilleServer))

        assertThat(texts.the(text(R.string.sign_in_again_title)).isEllipsized).isFalse()
        texts.the(line(camille)).assertWholeOnOneLine()
        texts.the(CamilleServer).assertWholeOnOneLine()
    }

    @Test
    fun `signing in again ends a long username and a long address with an ellipsis, on one line each`() {
        show(SignInPurpose.Again("p1"), current = camille, again = long)

        val texts = compose.assertAllTextFits(unbreakable = setOf(backTo(camille), line(long), longServer))

        assertThat(texts.the(text(R.string.sign_in_again_title)).isEllipsized).isFalse()
        texts.the(line(long)).assertCutOnOneLine()
        texts.the(longServer).assertCutOnOneLine()
    }

    @Test
    fun `the server step of adding a profile fits`() {
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = ServerUiState(address = longServer),
                    onAddressChange = {},
                    onContinue = {},
                    onCleartextConfirmed = {},
                    onCleartextDismissed = {},
                    purpose = SignInPurpose.Add,
                )
            }
        }

        val texts = compose.assertAllTextFits()

        assertThat(texts.map { it.text }).containsAtLeast(
            text(R.string.add_profile_title),
            text(R.string.add_profile_text),
        )
    }

    @Test
    fun `a bar with no profile to name shows the arrow alone`() {
        compose.setContent {
            LirelleTheme {
                Column {
                    BackToProfileBar(current = null, onBack = {})
                    Text("under the bar")
                }
            }
        }

        assertThat(compose.assertAllTextFits().map { it.text }).containsExactly("under the bar")
    }

    companion object {
        private const val Tablet = "w1280dp-h800dp-xhdpi"

        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w411dp-h891dp-420dpi", Tablet).flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
