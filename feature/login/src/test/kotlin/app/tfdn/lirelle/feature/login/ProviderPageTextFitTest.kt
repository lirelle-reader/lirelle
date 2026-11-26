package app.tfdn.lirelle.feature.login

import androidx.compose.ui.test.junit4.createComposeRule
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.TestUserAgent
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongHost = "authentification.bibliotheque-numerique.intranet.example-organisation.org"
private const val LongProvider = "Example Organisation Single Sign-On"

/**
 * The bar over a provider's page fits on a narrow phone and a tablet in landscape, in both languages, at the
 * default and the largest text size: the host of the page is shown whole, on as many lines as it needs — it tells
 * the user whose page asks for their password —, the provider's name on one line, cut with an ellipsis when long.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ProviderPageTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun show(providerName: String) {
        val page = ProviderPage(
            serverUrl = "https://$LongHost",
            kind = ServerKind.Komga,
            address = "https://$LongHost/oauth2/authorization/authentik",
            providerName = providerName,
        )
        compose.setContent {
            LirelleTheme { ProviderPageScreen(page, ProviderPageCookies(FakeWebCookies()), TestUserAgent, onEnd = {}) }
        }
    }

    @Test
    fun `a long host is shown whole under the provider's name`() {
        show("Authentik")

        val texts = compose.assertAllTextFits(unbreakable = setOf(LongHost))

        val host = texts.single { it.text == LongHost }
        assertWithMessage("$host is cut short").that(host.isEllipsized).isFalse()
        assertThat(host.isWhollyShown).isTrue()
        assertThat(texts.single { it.text == "Authentik" }.isEllipsized).isFalse()
    }

    @Test
    fun `a long provider's name stays on one line, and the host whole`() {
        show(LongProvider)

        val texts = compose.assertAllTextFits(unbreakable = setOf(LongHost, LongProvider))

        assertThat(texts.single { it.text == LongProvider }.lineCount).isEqualTo(1)
        val host = texts.single { it.text == LongHost }
        assertWithMessage("$host is cut short").that(host.isEllipsized).isFalse()
        assertThat(host.isWhollyShown).isTrue()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w1280dp-h800dp-xhdpi").flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
