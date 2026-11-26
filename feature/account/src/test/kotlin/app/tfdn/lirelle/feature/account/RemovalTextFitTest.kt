package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.kavitaRow
import app.tfdn.lirelle.feature.account.switcher.komgaRow
import app.tfdn.lirelle.feature.account.switcher.row
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongServer = "bibliotheque.famille-delacroix-de-la-fontaine.example.org:8443/livres"
private const val LongUsername = "marie-christine.delacroix-de-la-fontaine"

/**
 * No text of the removal's confirmation overflows, is clipped or breaks mid-word, in its three forms, on a
 * narrow phone, a phone and a tablet, in both languages, at the default and at the largest text size. Only the
 * profile's line and its server's address are cut, with an ellipsis.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class RemovalTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val profile = row("p0", LongUsername, LongServer, current = true, signInNeeded = true)

    private fun assertFits(cost: RemovalCost, profile: ProfileRow = this.profile) {
        compose.setContent { LirelleTheme { RemoveProfileDialog(profile, cost, onConfirm = {}, onDismiss = {}) } }

        val texts = compose.assertAllTextFits(unbreakable = setOf(profile.line, profile.server))

        assertThat(texts.filter { it.isEllipsized }.map { it.text }.toSet() - profile.line - profile.server).isEmpty()
        // Cancel and Remove.
        assertThat(texts.filter { it.isLabel }).hasSize(2)
    }

    @Test
    fun `the question about the current profile with nothing on the device fits`() = assertFits(RemovalCost.Counted())

    @Test
    fun `the question about a profile that sleeps fits`() {
        assertFits(RemovalCost.NotCounted, profile.copy(current = false))

        // Its own sentence: nothing says that it is signed out.
        compose.onNodeWithText(context.getString(R.string.profiles_remove_body_sleeping)).assertExists()
    }

    private val komga = komgaRow("p1", LongUsername, LongServer, current = true)

    @Test
    fun `the question about the current Komga profile, whose key is revoked, fits`() {
        assertFits(RemovalCost.Counted(), komga)

        compose.onNodeWithText(context.getString(R.string.profiles_remove_body_key)).assertExists()
    }

    @Test
    fun `the question about a Komga profile that sleeps, whose key is revoked, fits`() {
        assertFits(RemovalCost.NotCounted, komga.copy(current = false))

        compose.onNodeWithText(context.getString(R.string.profiles_remove_body_sleeping_key)).assertExists()
    }

    @Test
    fun `the question about a Kavita profile whose key the user pasted, which stays valid, fits`() {
        assertFits(RemovalCost.Counted(), kavitaRow("p1", LongUsername, LongServer, usersOwnKey = true, current = true))

        compose.onNodeWithText(context.getString(R.string.profiles_remove_body_own_key)).assertExists()
    }

    @Test
    fun `the question with downloads and everything that would be lost fits`() = assertFits(
        RemovalCost.Counted(
            books = 1_234,
            bytes = 123_400_000_000,
            lost = PendingCounts(positions = 12, sessions = 34, bookmarks = 56, highlights = 78),
        ),
    )

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
