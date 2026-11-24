package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Around = "around"

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ProfileButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ThreeProfiles)
    private var clicks = 0

    private val button get() = compose.onNodeWithTag(ProfileButtonTag)

    private fun show() {
        compose.setContent {
            LirelleTheme { Box(Modifier.testTag(Around)) { ProfileButton(state, onClick = { clicks++ }) } }
        }
    }

    @Test
    fun `with one profile there is no button, and it takes no room`() {
        state = SwitcherState(ThreeProfiles.rows.take(1))
        show()

        button.assertDoesNotExist()
        compose.onNodeWithTag(Around).assertWidthIsEqualTo(0.dp).assertHeightIsEqualTo(0.dp)
    }

    @Test
    fun `with nobody there is no button`() {
        state = SwitcherState()
        show()

        button.assertDoesNotExist()
    }

    @Test
    fun `from two profiles on it is a button a finger can hit, which names the current profile`() {
        state = SwitcherState(ThreeProfiles.rows.take(2))
        show()

        button.assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        button.assertHasClickAction()
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        button.assertContentDescriptionEquals("Profiles: camille, BookOrbit")
        // Nothing takes more room than the button itself: the avatar's badge stands out without pushing.
        compose.onNodeWithTag(Around).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)

        button.performClick()

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `it names the profile in French`() {
        show()

        button.assertContentDescriptionEquals("Profils : camille, BookOrbit")
    }

    @Test
    fun `it says that the current profile waits for a sign-in`() {
        state = SwitcherState(
            listOf(row("p2", "test", "livres.example.org", current = true, signInNeeded = true)) +
                ThreeProfiles.rows.take(1).map { it.copy(current = false) },
        )
        show()

        button.assertContentDescriptionEquals("Profiles: test, BookOrbit, sign-in needed")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `it says so in French`() {
        state = SwitcherState(
            listOf(row("p2", "test", "livres.example.org", current = true, signInNeeded = true)) +
                ThreeProfiles.rows.take(1).map { it.copy(current = false) },
        )
        show()

        button.assertContentDescriptionEquals("Profils : test, BookOrbit, reconnexion nécessaire")
    }

    @Test
    fun `it follows the current profile`() {
        show()
        button.assertContentDescriptionEquals("Profiles: camille, BookOrbit")

        state = SwitcherState(
            listOf(ThreeProfiles.rows[1].copy(current = true), ThreeProfiles.rows[0].copy(current = false)),
        )

        button.assertContentDescriptionEquals("Profiles: sacha, BookOrbit")
    }
}
