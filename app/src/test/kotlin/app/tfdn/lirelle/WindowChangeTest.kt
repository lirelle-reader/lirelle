package app.tfdn.lirelle

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.currentWindowWidthClass
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * A rotation, a resize or a fold is the activity's own business: it is not made again, which
 * would reload the reader's page, and what it shows follows the new window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-port")
class WindowChangeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /** The real activity, showing the width class as the app's root provides it. */
    private fun start(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller.get().setContent {
            CompositionLocalProvider(LocalWindowWidthClass provides currentWindowWidthClass()) {
                Text(LocalWindowWidthClass.current.name)
            }
        }
        return controller
    }

    private fun ActivityController<MainActivity>.change(qualifiers: String) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        configurationChange()
    }

    @Test
    fun `a rotation keeps the activity, and the width class follows the window`() {
        val controller = start()
        val activity = controller.get()
        compose.onNodeWithText("Compact").assertIsDisplayed()

        controller.change("w891dp-h411dp-land")

        assertThat(controller.get()).isSameInstanceAs(activity)
        assertThat(activity.isDestroyed).isFalse()
        compose.onNodeWithText("Expanded").assertIsDisplayed()

        controller.change("w411dp-h891dp-port")

        assertThat(activity.isDestroyed).isFalse()
        compose.onNodeWithText("Compact").assertIsDisplayed()
    }

    @Test
    fun `a resize and a keyboard plugged in keep the activity too`() {
        val controller = start()
        val activity = controller.get()

        controller.change("w700dp-h891dp")
        compose.onNodeWithText("Medium").assertIsDisplayed()
        controller.change("keysexposed-qwerty-dpad")

        assertThat(controller.get()).isSameInstanceAs(activity)
        assertThat(activity.isDestroyed).isFalse()
    }

    @Test
    fun `another language, the dark theme or another text size still make the activity again`() {
        listOf("fr", "night", "xhdpi").forEach { qualifiers ->
            val controller = start()
            val activity = controller.get()

            controller.change(qualifiers)

            assertThat(activity.isDestroyed).isTrue()
            controller.pause().stop().destroy()
        }
        val controller = start()
        val activity = controller.get()
        RuntimeEnvironment.setFontScale(2f)
        controller.configurationChange()
        assertThat(activity.isDestroyed).isTrue()
    }
}
