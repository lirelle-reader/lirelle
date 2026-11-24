package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What each stop shows, and the stop the reader is at found again when the screen counts the stops otherwise. */
class StopKeysTest {

    /** A phone held upright, and the same phone held sideways; the page as the fit lays it out on each. */
    private val upright = Size(1080f, 2340f)
    private val uprightPage = Rect(0f, 0f, 1080f, 1620f)
    private val sideways = Size(2340f, 1080f)
    private val sidewaysPage = Rect(0f, 0f, 720f, 1080f)

    /** A row as wide as the page, over two panels side by side. */
    private val rowAndTwo = PageZones.Cut(
        zones = listOf(
            Zone(0f, 0.05f, 1f, 0.25f, ZoneKind.Filled),
            Zone(0.05f, 0.3f, 0.45f, 0.6f, ZoneKind.Filled),
            Zone(0.55f, 0.3f, 0.95f, 0.6f, ZoneKind.Filled),
        ),
        millis = 0,
    )

    private fun keys(page: Rect, screen: Size, direction: Direction = Direction.LeftToRight): List<StopKey> =
        checkNotNull(keyedStops(listOf(StopPage(page, rowAndTwo)), screen, direction)).map { it.key }

    @Test
    fun `the row walked in three stops upright is one stop sideways, and the panels keep their zone`() {
        assertThat(keys(uprightPage, upright)).containsExactly(
            StopKey(0, 0, 0f),
            StopKey(0, 0, 0.5f),
            StopKey(0, 0, 1f),
            StopKey(0, 1, 0f),
            StopKey(0, 2, 0f),
        ).inOrder()
        assertThat(keys(sidewaysPage, sideways)).containsExactly(
            StopKey(0, 0, 0f),
            StopKey(0, 1, 0f),
            StopKey(0, 2, 0f),
        ).inOrder()
    }

    @Test
    fun `the stops keep their rects, the keys only say what each shows`() {
        val keyed = checkNotNull(keyedStops(listOf(StopPage(uprightPage, rowAndTwo)), upright, Direction.LeftToRight))
        val plain = spreadStops(listOf(StopPage(uprightPage, rowAndTwo)), upright, Direction.LeftToRight)
        assertThat(keyed.map { it.rect }).isEqualTo(plain)
    }

    @Test
    fun `read from the right, the stops of a row walked go from its start to its end in reading order`() {
        val keys = keys(uprightPage, upright, Direction.RightToLeft)
        assertThat(keys.take(3).map { it.along }).containsExactly(0f, 0.5f, 1f).inOrder()
    }

    @Test
    fun `a page shown whole beside a cut one is a stop of no zone, on its own page`() {
        val pages = listOf(
            StopPage(Rect(0f, 0f, 500f, 800f), rowAndTwo),
            StopPage(Rect(500f, 0f, 1000f, 800f), PageZones.Whole(WholePage.NoPanels)),
        )
        val keys = checkNotNull(keyedStops(pages, Size(1000f, 800f), Direction.LeftToRight)).map { it.key }
        assertThat(keys.last()).isEqualTo(StopKey(1, null, 0f))
    }

    @Test
    fun `a panel is found again at its new number, either way`() {
        val many = keys(uprightPage, upright)
        val few = keys(sidewaysPage, sideways)
        assertThat(sameStop(many[3], few)).isEqualTo(1)
        assertThat(sameStop(few[2], many)).isEqualTo(4)
    }

    @Test
    fun `any stop of the row is the row once it is one stop, and the row whole is its first stop once walked`() {
        val many = keys(uprightPage, upright)
        val few = keys(sidewaysPage, sideways)
        (0..2).forEach { assertThat(sameStop(many[it], few)).isEqualTo(0) }
        assertThat(sameStop(few[0], many)).isEqualTo(0)
    }

    @Test
    fun `a stop walked in three is the nearest stop of a walk in two, the first of two as near`() {
        val two = listOf(StopKey(0, 0, 0f), StopKey(0, 0, 1f))
        assertThat(sameStop(StopKey(0, 0, 0f), two)).isEqualTo(0)
        assertThat(sameStop(StopKey(0, 0, 0.5f), two)).isEqualTo(0)
        assertThat(sameStop(StopKey(0, 0, 1f), two)).isEqualTo(1)
    }

    @Test
    fun `a zone that no stop shows any more is not found`() {
        assertThat(sameStop(StopKey(0, 4, 0f), keys(sidewaysPage, sideways))).isNull()
        assertThat(sameStop(StopKey(1, 0, 0f), keys(sidewaysPage, sideways))).isNull()
    }

    @Test
    fun `the stops counted again on the same spread give the new number of the stop the reader is at`() {
        val counted = CountedStops()
        assertThat(counted.recount(5, keys(uprightPage, upright), at = 3)).isNull()

        assertThat(counted.recount(5, keys(sidewaysPage, sideways), at = 3)).isEqualTo(1)
        assertThat(counted.recount(5, keys(uprightPage, upright), at = 1)).isEqualTo(3)
    }

    @Test
    fun `the same stops, no stop, or another spread give no new number`() {
        val counted = CountedStops()
        counted.recount(5, keys(uprightPage, upright), at = 3)

        assertThat(counted.recount(5, keys(uprightPage, upright), at = 3)).isNull()
        assertThat(counted.recount(5, keys(sidewaysPage, sideways), at = null)).isNull()
        // Another spread: what the one before showed is not this one's.
        assertThat(counted.recount(6, keys(uprightPage, upright), at = 1)).isNull()
    }

    @Test
    fun `stops not known for a while are found again from the last ones known`() {
        val counted = CountedStops()
        counted.recount(5, keys(uprightPage, upright), at = 3)

        assertThat(counted.recount(5, null, at = 3)).isNull()
        assertThat(counted.recount(5, keys(sidewaysPage, sideways), at = 3)).isEqualTo(1)
    }
}
