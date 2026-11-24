package app.tfdn.lirelle.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.navigation.HomeDestination
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a screen that pads itself with the window insets gets, inside the shell. */
@RunWith(RobolectricTestRunner::class)
class ShellInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(width: WindowWidthClass) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    Shell(
                        account = {},
                        home = { paddedHome() },
                        library = { stubLibrary(it) },
                        search = { stubSearch(it) },
                    )
                }
            }
        }
    }

    /** Hands the window insets to the views, as the system does. Sizes are in pixels. */
    private fun applyInsets(build: WindowInsetsCompat.Builder.() -> Unit) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder().apply(build).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun window(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `above the bottom bar, an open keyboard pads the content down to the keyboard and no further`() {
        show(WindowWidthClass.Compact)

        applyInsets {
            setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, StatusBar, 0, 0))
            setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, SystemNavigation))
            setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, Keyboard))
        }

        assertThat(bounds("bottom-probe").bottom).isEqualTo(window().bottom - Keyboard)
        assertThat(bounds("top-probe").top).isEqualTo(StatusBar.toFloat())
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `with the keyboard closed, the content fills the space between the status bar and the bottom bar`() {
        show(WindowWidthClass.Compact)

        applyInsets {
            setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, StatusBar, 0, 0))
            setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, SystemNavigation))
        }

        assertThat(bounds("top-probe").top).isEqualTo(StatusBar.toFloat())
        assertThat(bounds("bottom-probe").bottom).isEqualTo(bounds("content").bottom)
        assertThat(bounds("content").bottom).isAtMost(bounds("nav-home").top)
        assertThat(bounds("content").bottom).isLessThan(window().bottom - SystemNavigation)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail, which stands clear of a display cutout, the content is not padded for it again`() {
        show(WindowWidthClass.Medium)

        applyInsets {
            setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(Cutout, 0, 0, 0))
        }

        assertThat(bounds("content").left).isGreaterThan(Cutout.toFloat())
        assertThat(bounds("top-probe").left).isEqualTo(bounds("content").left)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail, an open keyboard pads the content down to the keyboard`() {
        show(WindowWidthClass.Medium)

        applyInsets {
            setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, StatusBar, 0, 0))
            setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, SystemNavigation))
            setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, Keyboard))
        }

        assertThat(bounds("bottom-probe").bottom).isEqualTo(window().bottom - Keyboard)
        assertThat(bounds("top-probe").top).isEqualTo(StatusBar.toFloat())
    }
}

/** A Home that fills the content area and pads itself as the real screens do. */
private fun NavGraphBuilder.paddedHome() {
    composable<HomeDestination> {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("content")
                .safeDrawingPadding(),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(ProbeSize)
                    .testTag("top-probe"),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .size(ProbeSize)
                    .testTag("bottom-probe"),
            )
        }
    }
}

private val ProbeSize = 10.dp

// In pixels. The keyboard is taller than the bottom bar.
private const val StatusBar = 24
private const val SystemNavigation = 48
private const val Keyboard = 300
private const val Cutout = 200
