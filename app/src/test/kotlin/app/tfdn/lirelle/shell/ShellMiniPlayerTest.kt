package app.tfdn.lirelle.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the mini-player stands in the shell, and that the content above it is never covered. */
@RunWith(RobolectricTestRunner::class)
class ShellMiniPlayerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var loaded by mutableStateOf(true)
    private val snackbars = SnackbarHostState()

    private fun show(width: WindowWidthClass) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    Shell(
                        account = {},
                        home = { probedHome() },
                        library = { stubLibrary(it) },
                        search = { stubSearch(it) },
                        snackbars = snackbars,
                        // Like the real one: nothing at all while no book is loaded.
                        miniPlayer = { modifier ->
                            if (loaded) {
                                Box(
                                    modifier
                                        .fillMaxWidth()
                                        .height(MiniHeight)
                                        .testTag("mini"),
                                )
                            }
                        },
                    )
                }
            }
        }
        applyInsets()
    }

    /** A status bar and a system navigation bar, as on a device drawing edge to edge. Sizes are in pixels. */
    private fun applyInsets() {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, StatusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, SystemNavigation))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun window(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a phone, the mini-player sits right above the bottom bar, and the content ends above it`() {
        show(WindowWidthClass.Compact)

        val mini = bounds("mini")
        assertThat(mini.bottom).isAtMost(bounds("nav-home").top)
        // Not lifted off the bar by the system navigation, which the bar already stands clear of.
        assertThat(bounds("nav-home").top - mini.bottom).isLessThan(SystemNavigation.toFloat())
        assertThat(bounds("content").bottom).isAtMost(mini.top)
        assertThat(bounds("bottom-probe").bottom).isAtMost(mini.top)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail, the mini-player is at the bottom of the content area, clear of the system navigation`() {
        show(WindowWidthClass.Medium)

        val mini = bounds("mini")
        assertThat(mini.left).isAtLeast(bounds("nav-home").right)
        assertThat(mini.bottom).isAtMost(window().bottom - SystemNavigation)
        assertThat(bounds("content").bottom).isAtMost(mini.top)
        assertThat(bounds("bottom-probe").bottom).isAtMost(mini.top)
    }

    /** A message of the shell, shown until the test ends. */
    private fun showSnackbar() {
        compose.runOnUiThread {
            CoroutineScope(Dispatchers.Main).launch {
                snackbars.showSnackbar("Message", actionLabel = "Action", duration = SnackbarDuration.Indefinite)
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a phone, a snackbar stands above the mini-player and the bar, the content still clear of them`() {
        show(WindowWidthClass.Compact)

        showSnackbar()

        val mini = bounds("mini")
        val snackbar = bounds(ShellSnackbarsTag)
        assertThat(snackbar.height).isGreaterThan(0f)
        assertThat(snackbar.bottom).isAtMost(mini.top)
        assertThat(mini.bottom).isAtMost(bounds("nav-home").top)
        assertThat(bounds("content").bottom).isAtMost(mini.top)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail, a snackbar stands above the mini-player, clear of the rail`() {
        show(WindowWidthClass.Medium)

        showSnackbar()

        val mini = bounds("mini")
        val snackbar = bounds(ShellSnackbarsTag)
        assertThat(snackbar.bottom).isAtMost(mini.top)
        assertThat(snackbar.left).isAtLeast(bounds("nav-home").right)
        assertThat(bounds("content").bottom).isAtMost(mini.top)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail without a book loaded, a snackbar stays clear of the system navigation`() {
        show(WindowWidthClass.Medium)
        loaded = false
        compose.waitForIdle()

        showSnackbar()

        assertThat(bounds(ShellSnackbarsTag).bottom).isAtMost(window().bottom - SystemNavigation)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `without a book loaded, the content goes down to the bottom bar again`() {
        show(WindowWidthClass.Compact)
        val withMini = bounds("content").bottom

        loaded = false
        compose.waitForIdle()

        compose.onNodeWithTag("mini").assertDoesNotExist()
        assertThat(bounds("content").bottom).isGreaterThan(withMini)
        assertThat(bounds("content").bottom).isEqualTo(bounds("nav-home").top)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `beside the rail without a book loaded, the content goes down to the bottom of the window`() {
        show(WindowWidthClass.Medium)

        loaded = false
        compose.waitForIdle()

        assertThat(bounds("content").bottom).isEqualTo(window().bottom)
        assertThat(bounds("bottom-probe").bottom).isEqualTo(window().bottom - SystemNavigation)
    }
}

/** A Home that fills the content area and pads itself as the real screens do. */
private fun NavGraphBuilder.probedHome() {
    composable<HomeDestination> {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("content")
                .safeDrawingPadding(),
        ) {
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
private val MiniHeight = 64.dp

// In pixels.
private const val StatusBar = 24
private const val SystemNavigation = 48
