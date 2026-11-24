package app.tfdn.lirelle

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.copyCredential
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LirelleLevelsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val profiles = FakeOpenProfiles()
    private val rootViewModel = RootViewModel(profiles)

    /** Names of the probes, in the order they were created and cleared. */
    private val created = mutableListOf<String>()
    private val cleared = mutableListOf<String>()

    private val signedOut = SessionState.SignedOut("https://books.example.com", reason = null)
    private val session = bookOrbitSession(
        serverUrl = "https://books.example.com",
        sessionId = 1,
        tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
        user = UserProfile("1", "reader", "Ann Reader", null),
    )

    /** Another account, with a session of its own. */
    private val other = session.copyCredential(sessionId = 2).copy(user = UserProfile("2", "other", "Bob Other", null))

    private inner class Probe(private val name: String) : ViewModel() {
        init {
            created += name
        }

        override fun onCleared() {
            cleared += name
        }
    }

    /** A level built like the real ones: a NavHost whose destination owns a view model. */
    @Composable
    private fun ProbeLevel(name: String) {
        NavHost(navController = rememberNavController(), startDestination = "probe") {
            composable("probe") {
                viewModel(key = name) { Probe(name) }
                Text(name)
            }
        }
    }

    /** Sets the content on the current activity, as [MainActivity] does in `onCreate`. */
    private fun show() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val shown by rootViewModel.shown.collectAsState()
                LirelleTheme {
                    LirelleLevels(
                        shown = shown,
                        signIn = { ProbeLevel("sign-in") },
                        signedIn = { ProbeLevel("signed-in") },
                    )
                }
            }
        }
    }

    @Test
    fun `an update that could not finish shows its message alone, with no level`() {
        profiles.updateUnfinished()
        show()

        compose.onNodeWithText(compose.activity.getString(R.string.update_unfinished)).assertIsDisplayed()
        assertThat(created).isEmpty()
    }

    @Test
    fun `a change of profile shows the wait between two signed-in levels, each with its own view models`() {
        profiles.signIn("p1", session)
        show()
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        profiles.switching()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_changing))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("signed-in").assertDoesNotExist()
        compose.onNodeWithText("sign-in").assertDoesNotExist()
        assertThat(cleared).containsExactly("signed-in")

        profiles.signIn("p2", other)
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        // The sign-in level was never made: a change of profile does not go through it.
        assertThat(created).containsExactly("signed-in", "signed-in")
        assertThat(cleared).containsExactly("signed-in")
    }

    @Test
    fun `signing in clears the view models of the sign-in level`() {
        profiles.signOut(signedOut)
        show()
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        profiles.signIn("p1", session)
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        assertThat(cleared).containsExactly("sign-in")
    }

    @Test
    fun `signing out clears the view models of the signed-in level and starts a fresh sign-in level`() {
        profiles.signOut(signedOut)
        show()
        profiles.signIn("p1", session)
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        profiles.signOut(signedOut)
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        assertThat(cleared).containsExactly("sign-in", "signed-in").inOrder()
        assertThat(created).containsExactly("sign-in", "signed-in", "sign-in").inOrder()
    }

    @Test
    fun `a configuration change keeps the view models of the level`() {
        profiles.signOut(signedOut)
        show()
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        show()
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        assertThat(cleared).isEmpty()
        assertThat(created).containsExactly("sign-in")
    }

    @Test
    fun `a configuration change keeps the view models of the signed-in level`() {
        profiles.signIn("p1", session)
        show()
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        show()
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        assertThat(cleared).isEmpty()
        assertThat(created).containsExactly("signed-in")
    }

    @Test
    fun `remembering a server does not restart the sign-in level`() {
        profiles.signOut(SessionState.SignedOut(serverUrl = null, reason = null))
        show()
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        profiles.signOut(signedOut)
        compose.onNodeWithText("sign-in").assertIsDisplayed()

        assertThat(cleared).isEmpty()
        assertThat(created).containsExactly("sign-in")
    }

    @Test
    fun `renewed tokens keep the view models of the signed-in level`() {
        profiles.signIn("p1", session)
        show()
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        profiles.signIn("p1", session.copyCredential(tokens = Tokens("renewed", Instant.MAX, "refresh", Instant.MAX)))
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        assertThat(cleared).isEmpty()
        assertThat(created).containsExactly("signed-in")
    }

    @Test
    fun `another profile is another signed-in level, with its own view models`() {
        profiles.signIn("p1", session)
        show()
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        profiles.signIn("p2", other)
        compose.onNodeWithText("signed-in").assertIsDisplayed()

        assertThat(cleared).containsExactly("signed-in")
        assertThat(created).containsExactly("signed-in", "signed-in")
    }
}
