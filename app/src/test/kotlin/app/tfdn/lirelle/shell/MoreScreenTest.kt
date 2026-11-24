package app.tfdn.lirelle.shell

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MoreScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show(history: Boolean = true) {
        compose.setContent {
            LirelleTheme {
                MoreScreen(
                    onOpenProfiles = { opened += "profiles" },
                    onOpenHistory = if (history) ({ opened += "history" }) else null,
                    onOpenSettings = { opened += "settings" },
                    onOpenAbout = { opened += "about" },
                )
            }
        }
    }

    private fun top(name: Int): Float = compose.onNodeWithText(string(name)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `the four rows are Profiles, History, Settings and About, in that order`() {
        show()

        val rows = listOf(R.string.more_account, R.string.more_history, R.string.more_settings, R.string.more_about)
        rows.forEach { compose.onNodeWithText(string(it)).assertIsDisplayed() }
        assertThat(rows.map(::top)).isInStrictOrder()
    }

    @Test
    fun `the first row is Profiles, and says it holds the accounts, the servers and the sign-in`() {
        show()

        compose.onNodeWithText("Profiles").assertIsDisplayed()
        compose.onNodeWithText("Accounts, servers and sign-in").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the first row is Profils`() {
        show()

        compose.onNodeWithText("Profils").assertIsDisplayed()
        compose.onNodeWithText("Comptes, serveurs et connexion").assertIsDisplayed()
    }

    @Test
    fun `each row says what it holds`() {
        show()

        compose.onNodeWithText(string(R.string.more_settings_description)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.more_about_description)).assertIsDisplayed()
    }

    @Test
    fun `there is no row beside the four, and no Downloads section`() {
        show()

        // The player's row is gone: nothing else can be tapped.
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        compose.onNodeWithText(string(R.string.more_downloads)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.more_downloads_metered)).assertDoesNotExist()
    }

    @Test
    fun `the reading history entry is shown and opens the history`() {
        show()

        compose.onNodeWithText(string(R.string.more_history)).assertIsDisplayed().performClick()

        assertThat(opened).containsExactly("history")
    }

    @Test
    fun `without history there is no History row, and the three others stay in order`() {
        show(history = false)

        compose.onNodeWithText(string(R.string.more_history)).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        val rows = listOf(R.string.more_account, R.string.more_settings, R.string.more_about)
        rows.forEach { compose.onNodeWithText(string(it)).assertIsDisplayed() }
        assertThat(rows.map(::top)).isInStrictOrder()
    }

    @Test
    fun `the Settings row opens the settings`() {
        show()

        compose.onNodeWithText(string(R.string.more_settings)).performClick()

        assertThat(opened).containsExactly("settings")
    }

    @Test
    fun `the About row opens About`() {
        show()

        compose.onNodeWithText(string(R.string.more_about)).performClick()

        assertThat(opened).containsExactly("about")
    }
}
