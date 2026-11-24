package app.tfdn.lirelle.feature.comics.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.page.PanelReader.Companion.Back
import app.tfdn.lirelle.feature.comics.page.PanelReader.Companion.Forward
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A series read panel by panel, when the screen turns: the reader stays on the panel it reads, though a zone
 * walked in several stops on one screen is one stop on the other, so that the stops are not counted the same.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h900dp-mdpi")
class ComicSpreadPanelRotationTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val recording = RecordingNavigation()
    private var screen by mutableStateOf(Portrait)

    @Test
    fun `the panel read in portrait is the one read in landscape`() {
        show()
        // In portrait, the row is walked in three stops; in landscape it is one.
        val portrait = stopsOnScreen()
        assertThat(portrait).hasSize(RowStops + 4)

        // The row, then the first panel under it.
        repeat(RowStops + 1) { compose.tapAt(Forward) }
        assertAt(portrait[RowStops])

        screen = Landscape
        compose.waitForIdle()

        val landscape = stopsOnScreen(atStop = true)
        assertThat(landscape).hasSize(1 + 4)
        assertAt(landscape[1])

        // And the walk goes on from there: the second panel.
        compose.tapAt(Forward)
        assertAt(landscape[2])
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `the panel read in landscape is the one read in portrait`() {
        screen = Landscape
        show()
        val landscape = stopsOnScreen()
        // The row, the first panel, the second.
        repeat(3) { compose.tapAt(Forward) }
        assertAt(landscape[2])

        screen = Portrait
        compose.waitForIdle()

        val portrait = stopsOnScreen(atStop = true)
        assertAt(portrait[RowStops + 1])

        // Back: the first panel, then the end of the row.
        compose.tapAt(Back)
        assertAt(portrait[RowStops])
        compose.tapAt(Back)
        assertAt(portrait[RowStops - 1])
        assertThat(recording.calls).isEmpty()
    }

    @Test
    fun `the row read whole in landscape starts again at its beginning in portrait`() {
        screen = Landscape
        show()
        val landscape = stopsOnScreen()
        compose.tapAt(Forward)
        assertAt(landscape[0])

        screen = Portrait
        compose.waitForIdle()

        val portrait = stopsOnScreen(atStop = true)
        assertAt(portrait[0])
    }

    private fun show() {
        val page = pageImage(folder.root, Index, width = 400, height = 800)
        val state = album(spreadIndex = Index, pages = mapOf(Index to page)).copy(
            panels = readPanels().copy(zones = mapOf(Index to RowAndFour)),
        )
        val navigation = object : ComicNavigation by recording {}
        compose.setContent {
            images.Provide {
                LirelleTheme {
                    Box(Modifier.size(screen)) {
                        ComicSpreadView(state, navigation, onToggleBars = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The stops of the page on the screen as it is now, from where the page is drawn whole; [atStop], the page is
     * drawn at a stop, and its room is worked out from the screen as the fit lays it out.
     */
    private fun stopsOnScreen(atStop: Boolean = false): List<Rect> {
        val viewport = compose.viewport()
        val fit = minOf(viewport.width / 400f, viewport.height / 800f)
        val room = if (atStop) Rect(Offset.Zero, androidx.compose.ui.geometry.Size(400f * fit, 800f * fit))
        else Rect(Offset.Zero, page().size)
        return checkNotNull(spreadStops(listOf(StopPage(room, RowAndFour)), viewport.size, Direction.LeftToRight))
    }

    private fun page(): Rect = compose.onNodeWithContentDescription("Page ${Index + 1}").drawn()

    private fun assertAt(stop: Rect) {
        val viewport = compose.viewport()
        val place = stopPlace(stop, viewport.size)
        val page = page()
        val fit = minOf(viewport.width / 400f, viewport.height / 800f)
        assertThat(page.left).isWithin(StopSlack).of(place.offset.x)
        assertThat(page.top).isWithin(StopSlack).of(place.offset.y)
        assertThat(page.width).isWithin(StopSlack).of(400f * fit * place.zoom)
    }

    private companion object {
        const val Index = 5
        val Portrait = DpSize(400.dp, 800.dp)
        val Landscape = DpSize(800.dp, 400.dp)

        /** The stops the row is walked in, in portrait. */
        const val RowStops = 3

        /** A row as wide as the page over four panels, two a row. */
        val RowAndFour = PageZones.Cut(
            zones = listOf(
                Zone(0.05f, 0.03f, 0.95f, 0.2f, ZoneKind.Filled),
                Zone(0.05f, 0.25f, 0.45f, 0.5f, ZoneKind.Filled),
                Zone(0.55f, 0.25f, 0.95f, 0.5f, ZoneKind.Filled),
                Zone(0.05f, 0.55f, 0.45f, 0.8f, ZoneKind.Filled),
                Zone(0.55f, 0.55f, 0.95f, 0.8f, ZoneKind.Filled),
            ),
            millis = 0,
        )
    }
}
