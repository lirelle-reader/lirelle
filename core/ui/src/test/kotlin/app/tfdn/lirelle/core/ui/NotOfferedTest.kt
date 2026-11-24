package app.tfdn.lirelle.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A screen the source does not offer goes back once, when it comes to the front (spec 2c, "Errors"). */
@RunWith(RobolectricTestRunner::class)
class NotOfferedTest {

    @get:Rule
    val compose = createComposeRule()

    private class Entry : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val entry = Entry().apply { registry.currentState = Lifecycle.State.STARTED }
    private var backs = 0

    private fun moveTo(state: Lifecycle.State) {
        compose.runOnIdle { entry.registry.currentState = state }
        compose.waitForIdle()
    }

    @Test
    fun `it goes back exactly once, when it comes to the front and not before`() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides entry) { NotOffered(onBack = { backs++ }) }
            }
        }
        compose.waitForIdle()
        assertThat(backs).isEqualTo(0)

        moveTo(Lifecycle.State.RESUMED)
        assertThat(backs).isEqualTo(1)

        moveTo(Lifecycle.State.STARTED)
        moveTo(Lifecycle.State.RESUMED)
        assertThat(backs).isEqualTo(1)
    }
}
