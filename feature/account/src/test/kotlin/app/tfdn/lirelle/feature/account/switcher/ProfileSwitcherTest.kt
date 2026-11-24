package app.tfdn.lirelle.feature.account.switcher

import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.GripSheetTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Phone = "w411dp-h891dp"
private const val Tablet = "w1280dp-h800dp"

/** The profile button and what it opens: a sheet on a phone, a menu on a wide screen, the same list in both. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Phone)
class ProfileSwitcherTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(ThreeProfiles)
    private var shown by mutableStateOf(true)
    private val switched = mutableListOf<String>()
    private var added = 0
    private var managed = 0

    private val button get() = compose.onNodeWithTag(ProfileButtonTag)
    private val sheet get() = compose.onNodeWithTag(GripSheetTag)
    private val menu get() = compose.onNodeWithTag(ProfilesMenuTag)

    private fun rowOf(id: String) = compose.onNodeWithTag(ProfileRowTagPrefix + id)

    private fun show(host: ProfilesHost) {
        compose.setContent {
            LirelleTheme {
                if (shown) {
                    ProfileSwitcher(state, host, { switched += it }, { added++ }, { managed++ })
                }
            }
        }
    }

    private fun open(host: ProfilesHost) {
        show(host)
        button.performClick()
        rowOf("p0").assertExists()
    }

    private fun assertClosed() {
        compose.waitForIdle()
        rowOf("p0").assertDoesNotExist()
        sheet.assertDoesNotExist()
        menu.assertDoesNotExist()
    }

    /** Camille's profiles once Sacha is the current one, as the registry lists them after a switch. */
    private fun sachaCurrent() = SwitcherState(
        listOf(
            ThreeProfiles.rows[1].copy(current = true),
            ThreeProfiles.rows[0].copy(current = false),
            ThreeProfiles.rows[2],
        ),
    )

    @Test
    fun `nothing is open before the button is tapped`() {
        show(ProfilesHost.Sheet)

        button.assertExists()
        assertClosed()
    }

    @Test
    fun `on a phone the profiles open in a bottom sheet`() {
        open(ProfilesHost.Sheet)

        sheet.assertExists().assert(hasAnyAncestor(isDialog()))
        rowOf("p1").assert(hasAnyAncestor(hasTestTag(GripSheetTag)))
        compose.onNodeWithTag(ProfilesAddTag).assert(hasAnyAncestor(hasTestTag(GripSheetTag)))
        menu.assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `on a wide screen they open in a menu 380 dp wide, a window of its own with a title`() {
        open(ProfilesHost.Menu(24.dp))

        menu.assertExists().assert(hasAnyAncestor(isPopup())).assertWidthIsEqualTo(380.dp)
        menu.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Profiles"))
        rowOf("p1").assert(hasAnyAncestor(hasTestTag(ProfilesMenuTag)))
        compose.onNodeWithTag(ProfilesManageTag).assert(hasAnyAncestor(hasTestTag(ProfilesMenuTag)))
        sheet.assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `Back closes the menu`() {
        open(ProfilesHost.Menu(24.dp))
        val popup = (menu.fetchSemanticsNode().root as ViewRootForTest).view.parent as View

        compose.runOnUiThread {
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }

        assertClosed()
        button.assertExists()
    }

    @Test
    fun `a tap on another profile asks for the switch and closes the sheet`() {
        open(ProfilesHost.Sheet)

        rowOf("p1").performClick()

        assertThat(switched).containsExactly("p1")
        assertClosed()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `a tap on another profile asks for the switch and closes the menu, also when it waits for a sign-in`() {
        open(ProfilesHost.Menu(24.dp))

        rowOf("p2").performClick()

        assertThat(switched).containsExactly("p2")
        assertClosed()
    }

    @Test
    fun `a tap on the current profile only closes`() {
        open(ProfilesHost.Sheet)

        rowOf("p0").performClick()

        assertThat(switched).isEmpty()
        assertClosed()
    }

    @Test
    fun `Add a profile closes the sheet and opens the sign-in`() {
        open(ProfilesHost.Sheet)

        compose.onNodeWithTag(ProfilesAddTag).performClick()

        assertThat(added to managed).isEqualTo(1 to 0)
        assertClosed()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `Manage profiles closes the menu and opens the page`() {
        open(ProfilesHost.Menu(24.dp))

        compose.onNodeWithTag(ProfilesManageTag).performClick()

        assertThat(added to managed).isEqualTo(0 to 1)
        assertThat(switched).isEmpty()
        assertClosed()
    }

    @Test
    fun `the sheet closes when the current profile changes, and stays closed when it changes back`() {
        open(ProfilesHost.Sheet)

        state = sachaCurrent()
        assertClosed()
        state = ThreeProfiles

        assertClosed()
        button.assertExists()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `the menu closes when the current profile changes`() {
        open(ProfilesHost.Menu(24.dp))

        state = sachaCurrent()

        assertClosed()
    }

    @Test
    fun `the sheet goes with the button when one profile is left`() {
        open(ProfilesHost.Sheet)

        state = SwitcherState(ThreeProfiles.rows.take(1))

        assertClosed()
        button.assertDoesNotExist()
    }

    @Test
    fun `the screen torn down under the open sheet takes it away`() {
        open(ProfilesHost.Sheet)

        shown = false

        assertClosed()
    }

    @Test
    @Config(qualifiers = Tablet)
    fun `the screen torn down under the open menu takes it away`() {
        open(ProfilesHost.Menu(24.dp))

        shown = false

        assertClosed()
    }
}
