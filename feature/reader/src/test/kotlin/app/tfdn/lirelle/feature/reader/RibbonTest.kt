package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class RibbonTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val label get() = context.getString(R.string.reader_page_bookmarked)

    private val reading by lazy {
        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        val settings = ReaderSettings(theme = ReaderTheme.Sepia, header = nothing, footer = nothing)
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            location = location(),
            settings = settings,
            colours = ReaderTheme.Sepia.colours(systemDark = false),
        )
    }

    private val bookmarked by lazy { reading.copy(pageBookmarks = listOf(MarkId("b1"))) }

    private fun show(state: ReaderUiState, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ReaderContent(state = state, actions = RecordingActions(), engineSlot = { Box(it) })
                }
            }
        }
    }

    @Test
    fun `no ribbon while the page holds no bookmark`() {
        show(reading)

        compose.onNodeWithTag(RibbonTag).assertDoesNotExist()
    }

    @Test
    fun `no ribbon while a page turns, though the page holds a bookmark`() {
        show(bookmarked.copy(ribbonHidden = true))

        compose.onNodeWithTag(RibbonTag).assertDoesNotExist()
    }

    @Test
    fun `a page holding a bookmark has a ribbon with a spoken label, which does nothing itself`() {
        show(bookmarked)

        compose.onNodeWithContentDescription(label).assertIsDisplayed()
        val node = compose.onNodeWithTag(RibbonTag).fetchSemanticsNode()
        assertThat(node.config.getOrNull(SemanticsActions.OnClick)).isNull()
    }

    @Test
    fun `the ribbon hangs from the top-right corner of the page`() {
        show(bookmarked)

        assertTopRight()
    }

    @Test
    fun `the ribbon is not mirrored, neither in a right-to-left book nor in a right-to-left language`() {
        show(bookmarked.copy(rtl = true), LayoutDirection.Rtl)

        assertTopRight()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `with two pages side by side the ribbon is on the right-hand page`() {
        // The page lays the two pages out in its own slot: its top-right corner is the right-hand page's.
        show(bookmarked)

        assertTopRight()
    }

    @Test
    fun `a tap on the ribbon reaches the page under it`() {
        var taps = 0
        compose.setContent {
            LirelleTheme {
                ReaderContent(
                    state = bookmarked,
                    actions = RecordingActions(),
                    engineSlot = { Box(it.pointerInput(Unit) { detectTapGestures { taps++ } }) },
                )
            }
        }

        compose.onNodeWithTag(RibbonTag).performTouchInput { click(center) }

        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun `the ribbon is in the theme's ribbon colour`() {
        show(bookmarked)

        val pixels = compose.onNodeWithTag(RibbonTag).captureToImage().toPixelMap()
        val middle = pixels[pixels.width / 2, pixels.height / 3]
        assertThat(middle).isEqualTo(bookmarked.colours.ribbon)
    }

    private fun assertTopRight() {
        val page = compose.onNodeWithTag(ReaderPageTag).getUnclippedBoundsInRoot()
        val ribbon = compose.onNodeWithTag(RibbonTag).getUnclippedBoundsInRoot()
        assertThat(ribbon.top.value).isWithin(1f).of(page.top.value)
        assertThat(ribbon.right.value).isLessThan(page.right.value)
        assertThat(ribbon.left.value).isGreaterThan(page.left.value + (page.right.value - page.left.value) * 0.85f)
    }
}
