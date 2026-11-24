package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the rows' heights are asserted.
@Config(qualifiers = "w411dp-h891dp")
class ProfilesListTest {

    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private var added = 0
    private var managed = 0

    private fun show(state: SwitcherState = ThreeProfiles) {
        compose.setContent {
            LirelleTheme {
                ProfilesList(
                    state = state,
                    actions = ProfilesActions({ picked += it.id }, { added++ }, { managed++ }),
                    ground = MaterialTheme.colorScheme.surfaceContainerLow,
                    edge = 24.dp,
                )
            }
        }
    }

    private fun rowOf(id: String) = compose.onNodeWithTag(ProfileRowTagPrefix + id)

    private fun isButton() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun `it is headed Profiles, a heading`() {
        show()

        compose.onNodeWithText("Profiles").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `each profile has a row with its line and its server, in order of use`() {
        show()

        listOf(
            "p0" to listOf("camille · BookOrbit", "livres.example.org"),
            "p1" to listOf("sacha · BookOrbit", "bd.example.org"),
            "p2" to listOf("test · BookOrbit", "livres.example.org"),
        ).forEach { (id, texts) ->
            texts.forEach { text ->
                compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(ProfileRowTagPrefix + id)), true)
                    .assertCountEquals(1)
            }
            rowOf(id).assertHasClickAction().assert(isButton())
        }
        val tops = listOf("p0", "p1", "p2").map { rowOf(it).getUnclippedBoundsInRoot().top }
        assertThat(tops).isInStrictOrder()
    }

    @Test
    fun `a row is read as one thing, which says the current profile and the sign-in needed`() {
        show()

        rowOf("p0").assertContentDescriptionEquals("camille · BookOrbit, livres.example.org, current profile")
        rowOf("p1").assertContentDescriptionEquals("sacha · BookOrbit, bd.example.org")
        rowOf("p2").assertContentDescriptionEquals("test · BookOrbit, livres.example.org, sign-in needed")
    }

    @Test
    fun `a current profile that waits for a sign-in says both`() {
        show(SwitcherState(listOf(row("p2", "test", "nas.local", current = true, signInNeeded = true))))

        rowOf("p2").assertContentDescriptionEquals("test · BookOrbit, nas.local, current profile, sign-in needed")
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the rows are read in French`() {
        show()

        rowOf("p0").assertContentDescriptionEquals("camille · BookOrbit, livres.example.org, profil actuel")
        rowOf("p2").assertContentDescriptionEquals("test · BookOrbit, livres.example.org, reconnexion nécessaire")
        compose.onNodeWithText("Profils").assertExists()
        compose.onNodeWithText("Reconnexion nécessaire").assertExists()
        compose.onNodeWithText("Ajouter un profil").assertExists()
        compose.onNodeWithText("Gérer les profils").assertExists()
    }

    @Test
    fun `only the current profile is checked, and the check says nothing`() {
        show()

        compose.onAllNodesWithTag(ProfilesCheckTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(
            hasTestTag(ProfilesCheckTag) and hasAnyAncestor(hasTestTag(ProfileRowTagPrefix + "p0")),
            useUnmergedTree = true,
        ).assertExists()
        val check = compose.onNodeWithTag(ProfilesCheckTag, useUnmergedTree = true).fetchSemanticsNode()
        assertThat(check.config.getOrNull(SemanticsProperties.ContentDescription)).isNull()
    }

    @Test
    fun `only a profile that waits for a sign-in has the third line, and a taller row`() {
        show()

        compose.onAllNodes(hasText("Sign-in needed"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(
            hasText("Sign-in needed") and hasAnyAncestor(hasTestTag(ProfileRowTagPrefix + "p2")),
            useUnmergedTree = true,
        ).assertExists()
        assertThat(rowOf("p1").getUnclippedBoundsInRoot().height).isEqualTo(72.dp)
        assertThat(rowOf("p2").getUnclippedBoundsInRoot().height).isEqualTo(88.dp)
    }

    @Test
    fun `a tap on a row picks its profile`() {
        show()

        rowOf("p1").performClick()
        rowOf("p0").performClick()
        rowOf("p2").performClick()

        assertThat(picked).containsExactly("p1", "p0", "p2").inOrder()
        assertThat(added + managed).isEqualTo(0)
    }

    @Test
    fun `Add a profile and Manage profiles are buttons of a finger's height, each with its action`() {
        show()

        listOf(ProfilesAddTag to "Add a profile", ProfilesManageTag to "Manage profiles").forEach { (tag, label) ->
            val action = compose.onNodeWithTag(tag)
            action.assertHasClickAction().assert(isButton()).assert(hasText(label))
            assertThat(action.getUnclippedBoundsInRoot().height).isAtLeast(48.dp)
        }

        compose.onNodeWithTag(ProfilesAddTag).performClick()
        assertThat(added to managed).isEqualTo(1 to 0)
        compose.onNodeWithTag(ProfilesManageTag).performClick()
        assertThat(added to managed).isEqualTo(1 to 1)
        assertThat(picked).isEmpty()
    }
}
