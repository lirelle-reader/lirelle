package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnShownTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the action runs when the screen is first shown and each time it comes back in front`() {
        var shown = 0
        compose.setContent { OnShown(action = { shown++ }) }
        compose.waitForIdle()
        assertThat(shown).isEqualTo(1)

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        assertThat(shown).isEqualTo(1)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertThat(shown).isEqualTo(2)
    }

    @Test
    fun `the screen is said hidden each time it stops being in front`() {
        val seen = mutableListOf<String>()
        compose.setContent { OnShown(action = { seen += "shown" }, onHidden = { seen += "hidden" }) }
        compose.waitForIdle()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertThat(seen).containsExactly("shown", "hidden", "shown").inOrder()
    }
}
