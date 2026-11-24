package app.tfdn.lirelle.feature.login

import android.content.Context
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ServerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(
        state: ServerUiState,
        onContinue: () -> Unit = {},
        onCleartextConfirmed: () -> Unit = {},
    ) {
        compose.setContent {
            LirelleTheme {
                ServerScreen(
                    state = state,
                    onAddressChange = {},
                    onContinue = onContinue,
                    onCleartextConfirmed = onCleartextConfirmed,
                    onCleartextDismissed = {},
                )
            }
        }
    }

    @Test
    fun `continue reports the click`() {
        var clicks = 0
        show(ServerUiState(address = "books.example.com"), onContinue = { clicks++ })

        compose.onNodeWithText(context.getString(R.string.server_continue)).performClick()

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `continue is disabled while the server is being checked`() {
        show(ServerUiState(address = "books.example.com", isChecking = true))

        compose.onNodeWithText(context.getString(R.string.server_continue)).assertIsNotEnabled()
    }

    @Test
    fun `shows progress while the server is being checked`() {
        show(ServerUiState(address = "books.example.com", isChecking = true))

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun `shows no progress when nothing is being checked`() {
        show(ServerUiState(address = "books.example.com"))

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()
    }

    @Test
    fun `an invalid address shows its message`() {
        show(ServerUiState(address = "ftp://x", error = ServerError.InvalidAddress))

        compose.onNodeWithText(context.getString(R.string.server_error_invalid_address)).assertIsDisplayed()
    }

    @Test
    fun `an error message is announced`() {
        show(ServerUiState(address = "ftp://x", error = ServerError.InvalidAddress))

        compose.onNodeWithText(context.getString(R.string.server_error_invalid_address), useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `the address hint is not announced`() {
        show(ServerUiState(address = "books.example.com"))

        compose.onNodeWithText(context.getString(R.string.server_address_hint), useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `an api error shows its message`() {
        show(ServerUiState(address = "example.com", error = ServerError.Api(ApiError.UnknownServer)))

        compose.onNodeWithText(context.getString(R.string.error_unknown_server)).assertIsDisplayed()
    }

    @Test
    fun `the screen names no kind of server, and an unknown one is told as such`() {
        show(ServerUiState(address = "example.com", error = ServerError.Api(ApiError.UnknownServer)))

        compose.onNodeWithText("Connect to your server").assertIsDisplayed()
        compose.onNodeWithText("This address is not a BookOrbit or Komga server.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the screen names no kind of server, and an unknown one is told as such`() {
        show(ServerUiState(address = "example.com", error = ServerError.Api(ApiError.UnknownServer)))

        compose.onNodeWithText("Connexion à votre serveur").assertIsDisplayed()
        compose.onNodeWithText("Cette adresse n'est ni un serveur BookOrbit ni un serveur Komga.").assertIsDisplayed()
    }

    @Test
    fun `the http warning names the address and confirms`() {
        var confirmed = 0
        show(
            ServerUiState(address = "http://homeserver:3000", cleartextAddress = "http://homeserver:3000"),
            onCleartextConfirmed = { confirmed++ },
        )

        compose.onNodeWithText(context.getString(R.string.cleartext_dialog_message, "http://homeserver:3000"))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cleartext_dialog_confirm)).performClick()

        assertThat(confirmed).isEqualTo(1)
    }
}
