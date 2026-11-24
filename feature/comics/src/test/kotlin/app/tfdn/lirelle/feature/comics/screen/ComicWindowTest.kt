package app.tfdn.lirelle.feature.comics.screen

import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.comics.ComicHostEvents
import app.tfdn.lirelle.feature.comics.ComicHostReports
import app.tfdn.lirelle.feature.comics.ComicReaderEvent
import app.tfdn.lirelle.feature.comics.ComicReaderEvents
import app.tfdn.lirelle.feature.comics.ComicWindowEffects
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the screen does to its window, tells the reader of it, and does with what the reader asks once. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var inFront by mutableStateOf(true)

    private val host = object : ComicHostEvents {
        val calls = mutableListOf<String>()

        override fun windowSize(widthDp: Int, heightDp: Int) {
            calls += "size ${widthDp}x$heightDp"
        }

        override fun buildDebuggable(debuggable: Boolean) = Unit

        override fun onForeground() {
            calls += "foreground"
        }

        override fun onBackground() {
            calls += "background"
        }
    }

    @Test
    fun `the system bars' icons are light over a dark ground, dark over a light one, and as before once left`() {
        var darkGround by mutableStateOf(false)
        val before = lightIcons()
        compose.setContent { if (inFront) ComicWindowEffects(barsOver = true, darkGround) }
        compose.waitForIdle()
        assertThat(lightIcons()).isEqualTo(LightBars)

        darkGround = true
        compose.waitForIdle()
        assertThat(lightIcons()).isEqualTo(0)

        darkGround = false
        compose.waitForIdle()
        inFront = false
        compose.waitForIdle()

        assertThat(lightIcons()).isEqualTo(before)
    }

    @Test
    fun `the system bars are hidden while the reader's bars are, shown with them, and shown once it is left`() {
        var barsOver by mutableStateOf(false)
        compose.setContent { if (inFront) ComicWindowEffects(barsOver, darkGround = true) }
        compose.waitForIdle()
        assertThat(statusBarShown()).isFalse()

        barsOver = true
        compose.waitForIdle()
        assertThat(statusBarShown()).isTrue()

        barsOver = false
        compose.waitForIdle()
        assertThat(statusBarShown()).isFalse()

        inFront = false
        compose.waitForIdle()

        assertThat(statusBarShown()).isTrue()
    }

    @Test
    fun `the screen stays on while the reader is shown, and no longer once it is left`() {
        compose.setContent { if (inFront) ComicWindowEffects(barsOver = false, darkGround = true) }
        compose.waitForIdle()
        assertThat(keptOn()).isTrue()

        inFront = false
        compose.waitForIdle()

        assertThat(keptOn()).isFalse()
    }

    @Test
    fun `the reader is told the size of the window, and when it is in front`() {
        compose.setContent { if (inFront) ComicHostReports(host) }
        compose.waitForIdle()
        assertThat(host.calls).containsExactly("size 411x891", "foreground")

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertThat(host.calls.last()).isEqualTo("background")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertThat(host.calls.last()).isEqualTo("foreground")

        inFront = false
        compose.waitForIdle()

        assertThat(host.calls).containsExactly("size 411x891", "foreground", "background", "foreground", "background")
            .inOrder()
    }

    @Test
    fun `what the reader asks once goes to the screen's callers`() {
        val events = Channel<ComicReaderEvent>(Channel.UNLIMITED)
        val heard = mutableListOf<String>()
        compose.setContent {
            ComicReaderEvents(
                events = events.receiveAsFlow(),
                onLeave = { heard += "leave" },
                onOpenNext = { heard += "next $it" },
            )
        }

        events.trySend(ComicReaderEvent.OpenNext(BookId("15")))
        events.trySend(ComicReaderEvent.Leave)
        compose.waitForIdle()

        assertThat(heard).containsExactly("next 15", "leave").inOrder()
    }

    /** Which of the system bars have icons for a light ground. */
    private fun lightIcons(): Int {
        var appearance = 0
        compose.activityRule.scenario.onActivity {
            appearance = checkNotNull(it.window.insetsController).systemBarsAppearance and LightBars
        }
        return appearance
    }

    private fun statusBarShown(): Boolean? {
        var shown: Boolean? = null
        compose.activityRule.scenario.onActivity {
            shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars())
        }
        return shown
    }

    private fun keptOn(): Boolean {
        var flags = 0
        compose.activityRule.scenario.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    }

    private companion object {
        const val LightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    }
}
