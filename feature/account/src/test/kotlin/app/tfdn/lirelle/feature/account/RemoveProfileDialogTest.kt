package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.account.switcher.ProfileRow
import app.tfdn.lirelle.feature.account.switcher.audiobookshelfRow
import app.tfdn.lirelle.feature.account.switcher.kavitaRow
import app.tfdn.lirelle.feature.account.switcher.komgaRow
import app.tfdn.lirelle.feature.account.switcher.row
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Bytes = 1_400_000_000L

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi")
class RemoveProfileDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val camille = row("p0", "camille", "livres.example.org", current = true)
    private val sacha = row("p1", "sacha", "bd.example.org")
    private val komga = komgaRow("p3", "ann@example.com", "bd.example.org")
    private var confirmed = 0
    private var dismissed = 0

    private fun string(id: Int) = context.getString(id)

    private fun show(cost: RemovalCost, profile: ProfileRow = camille) {
        compose.setContent {
            LirelleTheme {
                RemoveProfileDialog(profile, cost, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
    }

    /** @param body what the removal does: a sign-out for the current profile, not for one that sleeps. */
    private fun assertAsksAbout(line: String, server: String, body: Int = R.string.profiles_remove_body) {
        compose.onNodeWithText(string(R.string.profiles_remove_title)).assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(string(body)).assertIsDisplayed()
        compose.onNodeWithText(line, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(server, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `for the current profile with downloads and writes at risk it counts both`() {
        val cost = RemovalCost.Counted(books = 12, bytes = Bytes, lost = PendingCounts(positions = 2, highlights = 1))
        show(cost)

        assertAsksAbout("camille · BookOrbit", "livres.example.org")
        val lines = removalLines(context, cost)
        compose.onNodeWithTag(RemovalKeptTag).assertIsDisplayed()
        compose.onNodeWithText(checkNotNull(lines.kept)).assertIsDisplayed()
        compose.onNodeWithTag(RemovalAtRiskTag).assertIsDisplayed()
        compose.onNodeWithText(checkNotNull(lines.atRisk)).assertIsDisplayed()
    }

    @Test
    fun `for the current profile with nothing on the device it only says what a removal does`() {
        show(RemovalCost.Counted())

        assertAsksAbout("camille · BookOrbit", "livres.example.org")
        compose.onNodeWithTag(RemovalKeptTag).assertDoesNotExist()
        compose.onNodeWithTag(RemovalAtRiskTag).assertDoesNotExist()
    }

    @Test
    fun `writes at risk are said when nothing is downloaded too`() {
        show(RemovalCost.Counted(lost = PendingCounts(highlights = 1)))

        compose.onNodeWithTag(RemovalKeptTag).assertDoesNotExist()
        compose.onNodeWithTag(RemovalAtRiskTag).assertIsDisplayed()
    }

    @Test
    fun `for a profile that sleeps it names it, claims no sign-out, and says what is erased without numbers`() {
        show(RemovalCost.NotCounted, sacha)

        assertAsksAbout("sacha · BookOrbit", "bd.example.org", R.string.profiles_remove_body_sleeping)
        compose.onNodeWithText(string(R.string.profiles_remove_body)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.profiles_remove_sleeping)).assertIsDisplayed()
        compose.onNodeWithTag(RemovalAtRiskTag).assertDoesNotExist()
    }

    @Test
    fun `for a Komga profile that sleeps it says its key will be revoked, not that nothing changes on the server`() {
        show(RemovalCost.NotCounted, komga)

        compose.onNodeWithText(
            "Everything the device keeps for this profile will be erased. Its key will be revoked on the server.",
        ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.profiles_remove_body_sleeping)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.profiles_remove_sleeping)).assertIsDisplayed()
    }

    @Test
    fun `for a Kavita profile whose key the app made it says the key will be revoked, as for Komga`() {
        show(RemovalCost.NotCounted, kavitaRow("p4", "ann", "bd.example.org", usersOwnKey = false))

        compose.onNodeWithText(string(R.string.profiles_remove_body_sleeping_key)).assertIsDisplayed()
    }

    @Test
    fun `for the current Kavita profile whose key the app made it says it is signed out and its key revoked`() {
        show(RemovalCost.Counted(), kavitaRow("p4", "ann", "bd.example.org", usersOwnKey = false, current = true))

        compose.onNodeWithText(string(R.string.profiles_remove_body_key)).assertIsDisplayed()
    }

    @Test
    fun `for a profile whose key the user pasted it never says the key will be revoked, current or not`() {
        val pasted = kavitaRow("p4", "ann", "bd.example.org", usersOwnKey = true)

        listOf(pasted, pasted.copy(current = true)).forEach { profile ->
            assertThat(string(removalBody(profile))).isEqualTo(
                "Everything the device keeps for this profile will be erased. " +
                    "The key you provided stays valid on the server.",
            )
        }
        show(RemovalCost.NotCounted, pasted)
        compose.onNodeWithText(string(R.string.profiles_remove_body_own_key)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.profiles_remove_body_sleeping_key)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-xhdpi")
    fun `the key the user pasted is said in French to stay valid`() {
        show(RemovalCost.NotCounted, kavitaRow("p4", "ann", "bd.example.org", usersOwnKey = true))

        compose.onNodeWithText(
            "Tout ce que l'appareil garde pour ce profil sera effacé. " +
                "La clé que vous avez fournie reste valable sur le serveur.",
        ).assertIsDisplayed()
    }

    @Test
    fun `for the current Komga profile it says it is signed out and its key revoked`() {
        show(RemovalCost.Counted(), komga.copy(current = true))

        compose.onNodeWithText(
            "This profile will be signed out and everything the device keeps for it will be erased. " +
                "Its key will be revoked on the server.",
        ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.profiles_remove_body)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-xhdpi")
    fun `the key's revocation is said in French`() {
        show(RemovalCost.NotCounted, komga)

        compose.onNodeWithText(
            "Tout ce que l'appareil garde pour ce profil sera effacé. Sa clé sera révoquée sur le serveur.",
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-xhdpi")
    fun `the current Komga profile's removal is said in French`() {
        show(RemovalCost.Counted(), komga.copy(current = true))

        compose.onNodeWithText(
            "Ce profil sera déconnecté et tout ce que l'appareil garde pour lui sera effacé. " +
                "Sa clé sera révoquée sur le serveur.",
        ).assertIsDisplayed()
    }

    @Test
    fun `for the current Audiobookshelf profile it says it is signed out and its session closed on the server`() {
        show(RemovalCost.Counted(), audiobookshelfRow("p5", "ann", "audio.example.org", current = true))

        compose.onNodeWithText(
            "This profile will be signed out and everything the device keeps for it will be erased. " +
                "Its session will be closed on the server.",
        ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.profiles_remove_body)).assertDoesNotExist()
    }

    @Test
    fun `for an Audiobookshelf profile that sleeps it says its session will be closed, not that it is signed out`() {
        show(RemovalCost.NotCounted, audiobookshelfRow("p5", "ann", "audio.example.org"))

        compose.onNodeWithText(
            "Everything the device keeps for this profile will be erased. Its session will be closed on the server.",
        ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.profiles_remove_body_sleeping)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-xhdpi")
    fun `the current Audiobookshelf profile's removal is said in French`() {
        show(RemovalCost.Counted(), audiobookshelfRow("p5", "ann", "audio.example.org", current = true))

        compose.onNodeWithText(
            "Ce profil sera déconnecté et tout ce que l'appareil garde pour lui sera effacé. " +
                "Sa session sera fermée sur le serveur.",
        ).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp-xhdpi")
    fun `the closing of a sleeping Audiobookshelf profile's session is said in French`() {
        show(RemovalCost.NotCounted, audiobookshelfRow("p5", "ann", "audio.example.org"))

        compose.onNodeWithText(
            "Tout ce que l'appareil garde pour ce profil sera effacé. Sa session sera fermée sur le serveur.",
        ).assertIsDisplayed()
    }

    @Test
    fun `the profile asked about is read as one thing`() {
        show(RemovalCost.NotCounted, sacha)

        compose.onNodeWithTag(RemovalProfileTag).assertContentDescriptionEquals("sacha · BookOrbit, bd.example.org")
    }

    @Test
    fun `Remove confirms`() {
        show(RemovalCost.Counted(books = 5, bytes = Bytes))

        compose.onNodeWithText(string(R.string.profiles_remove_confirm)).performClick()

        assertThat(confirmed).isEqualTo(1)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun `Cancel dismisses`() {
        show(RemovalCost.Counted(books = 5, bytes = Bytes))

        compose.onNodeWithText(string(R.string.profiles_remove_cancel)).performClick()

        assertThat(confirmed).isEqualTo(0)
        assertThat(dismissed).isEqualTo(1)
    }
}
