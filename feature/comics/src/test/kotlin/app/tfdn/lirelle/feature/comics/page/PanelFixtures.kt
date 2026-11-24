package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.PanelReading
import com.google.common.truth.Truth.assertThat

/** A page cut into three panels, two side by side over a third: each is one stop on the screen of these tests. */
internal val ThreePanels = PageZones.Cut(
    zones = listOf(
        Zone(0.1f, 0.1f, 0.45f, 0.4f, ZoneKind.Filled),
        Zone(0.55f, 0.1f, 0.9f, 0.4f, ZoneKind.Filled),
        Zone(0.3f, 0.6f, 0.7f, 0.9f, ZoneKind.Filled),
    ),
    millis = 0,
)

/** How near, in pixels, the spread is to be to where a stop puts it. */
internal const val StopSlack = 1.5f

/** A series read panel by panel, the [pages] given each cut as [ThreePanels]. */
internal fun readPanels(vararg pages: Int, wholePageFirst: Boolean = true) = PanelReading(
    offered = true,
    on = true,
    wholePageFirst = wholePageFirst,
    zones = pages.associateWith { ThreePanels },
)

/**
 * The page view of a series read panel by panel, on the spread 5 of an album whose pages are loading: such a
 * page has the shape of the screen, so the spread at the fit is the screen itself. The reader here takes the
 * turns it is asked for, as the real one does.
 */
internal class PanelReader(private val compose: ComposeContentTestRule, private val images: PageImages) {
    val recording = RecordingNavigation()
    var state by mutableStateOf(album(spreadIndex = 5).copy(panels = readPanels(4, 5, 6)))

    /** How many times the bars were asked to go. */
    var hides = 0
        private set

    /** How many times a tap in the middle asked for the bars. */
    var toggles = 0
        private set

    /** Whether the reader takes the turns it is asked for; false, it stays on the spread it shows. */
    var takesTurns = true

    private val navigation = object : ComicNavigation by recording {
        override fun next() {
            recording.next()
            if (takesTurns && state.spreadIndex < state.spreads.lastIndex) turnTo(state.spreadIndex + 1, forward = true)
        }

        override fun previous() {
            recording.previous()
            if (takesTurns && state.spreadIndex > 0) turnTo(state.spreadIndex - 1, forward = false)
        }
    }

    val screen: Rect get() = compose.viewport()

    fun show(shown: ComicReaderUiState = state) {
        state = shown
        compose.setContent {
            images.Provide {
                LirelleTheme {
                    ComicSpreadView(state, navigation, onToggleBars = { toggles++ }, onHideBars = { hides++ })
                }
            }
        }
    }

    /** Where the page of that number (from 1) is drawn. */
    fun page(number: Int): Rect = compose.onNodeWithContentDescription("Page $number").drawn()

    /** The stops of a page cut as [ThreePanels] that is the whole screen. */
    fun stops(direction: Direction = Direction.LeftToRight): List<Rect> {
        val page = StopPage(Rect(Offset.Zero, screen.size), ThreePanels)
        return checkNotNull(spreadStops(listOf(page), screen.size, direction)).also { assertThat(it).hasSize(3) }
    }

    /** The page of that [number], which is its whole spread, is where the stop of that [index] puts it. */
    fun assertAtStop(number: Int, index: Int) = assertAt(number, stops()[index])

    /** The page of that [number], which is its whole spread, is where that [stop] of its content puts it. */
    fun assertAt(number: Int, stop: Rect) {
        val place = stopPlace(stop, screen.size)
        val page = page(number)
        assertThat(page.left).isWithin(StopSlack).of(place.offset.x)
        assertThat(page.top).isWithin(StopSlack).of(place.offset.y)
        assertThat(page.width).isWithin(StopSlack).of(screen.width * place.zoom)
    }

    /** The page of that [number] is shown whole: it is the screen. */
    fun assertWhole(number: Int) {
        val page = page(number)
        assertThat(page.left).isWithin(StopSlack).of(0f)
        assertThat(page.top).isWithin(StopSlack).of(0f)
        assertThat(page.width).isWithin(StopSlack).of(screen.width)
    }

    private fun turnTo(index: Int, forward: Boolean) {
        state = state.copy(spreadIndex = index, forward = forward)
    }

    companion object {
        const val Forward = 0.9f
        const val Back = 0.1f
    }
}
