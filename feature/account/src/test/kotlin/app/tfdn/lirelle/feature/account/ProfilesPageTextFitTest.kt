package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.account.switcher.row
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongServer = "bibliotheque.famille-delacroix-de-la-fontaine.example.org:8443/livres"
private const val LongUsername = "marie-christine.delacroix-de-la-fontaine"
private const val LongEmail = "marie-christine.delacroix-de-la-fontaine@example-organisation.org"
private const val LongVersion = "v3.8.0-rc.12+build.20261113"

/**
 * No text of the Profiles page, nor of the menu of a row, overflows, is clipped or breaks mid-word, on a narrow
 * phone, a phone and a tablet in landscape with its two columns, in both languages, at the default and at the
 * largest text size. Only a server's address in a row and a "`<username>` · `<kind>`" line are cut, with an
 * ellipsis; the labels of the page's buttons take a second line instead.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ProfilesPageTextFitTest(
    screen: String,
    private val width: WindowWidthClass,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loaded = AccountUiState.Loaded(
        AccountInfo("Camille Lecteur", LongUsername, LongEmail, "https://$LongServer", LongVersion),
    )
    private val current = CurrentProfileDetails(
        row = row("p0", LongUsername, LongServer, current = true, signInNeeded = true),
        name = "Marie-Christine Delacroix de la Fontaine",
        email = LongEmail,
        serverUrl = "https://$LongServer",
    )
    private val others = listOf(
        row("p1", "sacha", "bd.example.org"),
        row("p2", LongUsername, LongServer, signInNeeded = true),
    )
    private val lines = (others + current.row).map { it.line }.toSet()
    private val servers = (others + current.row).map { it.server }.toSet()

    private fun text(id: Int) = context.getString(id)

    private fun show(state: ProfilesPageState, details: AccountUiState = loaded) {
        val actions = ProfilesPageActions({}, {}, {}, {}, {}, {}, {})
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    ProfilesScreen(state, details, actions = actions)
                }
            }
        }
    }

    /**
     * The labels that take a second line rather than being cut: the page's own buttons, a row's mark, a menu's
     * item. Not a profile's line: it stays on one.
     */
    private val wrapping
        get() = setOf(
            text(R.string.profiles_add),
            text(R.string.profiles_remove),
            text(CoreR.string.sign_in_again),
            text(CoreR.string.sign_in_needed),
        )

    /** A username, an address, a version have nowhere to break: these may break anywhere, or be cut. */
    private val unbreakable = lines + servers + setOf(LongUsername, LongEmail, "https://$LongServer", LongVersion)

    private fun assertFits(vararg shown: String): List<LaidOutText> {
        val texts = compose.assertAllTextFits(unbreakable = unbreakable, wrappingLabels = wrapping)
        // Nothing but a row's server and a profile's line is ever cut, and each of these is on one line.
        assertThat(texts.filter { it.isEllipsized }.map { it.text }.toSet() - servers - lines).isEmpty()
        texts.filter { it.text in servers || it.text in lines }
            .forEach { assertWithMessage("lines of $it").that(it.lineCount).isEqualTo(1) }
        assertThat(texts.map { it.text }).containsAtLeastElementsIn(shown.toList())
        return texts
    }

    @Test
    fun `the page of one profile fits with long values`() {
        show(ProfilesPageState(current.copy(row = current.row.copy(signInNeeded = false))))

        assertFits(
            text(R.string.profiles_title),
            current.name,
            LongEmail,
            LongVersion,
            text(R.string.profiles_add),
            text(R.string.profiles_remove),
        )
    }

    @Test
    fun `the page of three profiles fits with long values and both marks`() {
        show(ProfilesPageState(current, others))

        assertFits(
            text(R.string.profiles_current),
            text(R.string.profiles_others),
            text(CoreR.string.sign_in_needed),
            text(CoreR.string.sign_in_again),
            "sacha · BookOrbit",
            text(R.string.profiles_add),
            text(R.string.profiles_remove),
        )
    }

    @Test
    fun `the page of one profile that waits for a sign-in fits, with its button`() {
        show(ProfilesPageState(current))

        assertFits(
            text(CoreR.string.sign_in_needed),
            text(CoreR.string.sign_in_again),
            text(R.string.profiles_add),
            text(R.string.profiles_remove),
        )
    }

    @Test
    fun `the server that could not be asked fits`() {
        show(
            ProfilesPageState(current.copy(row = current.row.copy(signInNeeded = false)), others),
            details = AccountUiState.Failed(ApiError.ServerUnreachable),
        )

        assertFits(text(R.string.account_error_unreachable), text(R.string.account_retry))
    }

    @Test
    fun `the menu of a row fits`() {
        show(ProfilesPageState(current, others))
        compose.onNodeWithTag(ProfileActionsTagPrefix + "p2").performScrollTo().performClick()

        // "Remove this profile" is there twice: the current profile's button, and the menu's item.
        val texts = assertFits(text(CoreR.string.sign_in_again))
        assertThat(texts.count { it.text == text(R.string.profiles_remove) }).isEqualTo(2)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {2}, font scale {3}")
        fun conditions(): List<Array<Any>> = listOf(
            "w320dp-h640dp-xhdpi" to WindowWidthClass.Compact,
            "w411dp-h891dp-420dpi" to WindowWidthClass.Compact,
            "w1280dp-h800dp-xhdpi" to WindowWidthClass.Expanded,
        ).flatMap { (screen, width) ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, width, language, fontScale) }
            }
        }
    }
}
