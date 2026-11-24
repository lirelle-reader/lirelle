package app.tfdn.lirelle.feature.comics.page

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.Direction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val Near = 0.01f

/** From the zones of a page to the stops of the reader: the margin, the zones too large, the spread, the place. */
class PanelStopsTest {

    /** A phone held upright, and a page two thirds as wide as it is high, as wide as the screen. */
    private val phone = Size(1080f, 2340f)
    private val page = Rect(0f, 0f, 1080f, 1620f)

    /** The margin on that page: 2 % of its width. */
    private val margin = 21.6f

    /** A tablet held sideways, and the same page as high as its screen. */
    private val tablet = Size(2560f, 1600f)
    private val tabletPage = Rect(0f, 0f, 1067f, 1600f)

    /** A row as wide as the page and a fifth of its height. */
    private val row = zone(0f, 0.4f, 1f, 0.6f)

    /** The page whole. */
    private val all = zone(0f, 0f, 1f, 1f)

    private fun zone(left: Float, top: Float, right: Float, bottom: Float) =
        Zone(left, top, right, bottom, ZoneKind.Filled)

    private fun stops(zone: Zone, direction: Direction = Direction.LeftToRight) =
        pageStops(listOf(zone), page, phone, direction)

    private fun assertSame(got: Rect, want: Rect) {
        assertThat(got.left).isWithin(Near).of(want.left)
        assertThat(got.top).isWithin(Near).of(want.top)
        assertThat(got.right).isWithin(Near).of(want.right)
        assertThat(got.bottom).isWithin(Near).of(want.bottom)
    }

    @Test
    fun `a page of six zones gives six stops in their order, each its zone wider by the margin`() {
        val columns = listOf(0.05f to 0.48f, 0.52f to 0.95f)
        val rows = listOf(0.05f to 0.32f, 0.36f to 0.64f, 0.68f to 0.95f)
        val zones = rows.flatMap { (top, bottom) -> columns.map { (left, right) -> zone(left, top, right, bottom) } }

        val stops = pageStops(zones, page, phone, Direction.LeftToRight)

        assertThat(stops).hasSize(6)
        zones.zip(stops).forEach { (zone, stop) ->
            val box = zoneRect(zone, page.size)
            assertSame(stop, Rect(box.left - margin, box.top - margin, box.right + margin, box.bottom + margin))
        }
    }

    @Test
    fun `a zone against the edge of the page is not widened past it`() {
        assertSame(stops(zone(0f, 0f, 0.5f, 0.3f)).single(), Rect(0f, 0f, 540f + margin, 486f + margin))
        assertSame(stops(zone(0.5f, 0.7f, 1f, 1f)).single(), Rect(540f - margin, 1134f - margin, 1080f, 1620f))
    }

    @Test
    fun `two zones a thin gutter apart each stop at its middle, and their stops touch`() {
        // On the page 1080 wide, the first ends at 0.495 x 1080 = 534.6 and the second begins at 0.505 x 1080 =
        // 545.4: a gutter of 10.8, 1 % of the width, under twice the margin of 21.6. Each stops at its middle,
        // (534.6 + 545.4) / 2 = 540. The other sides have no neighbour: 108 - 21.6 = 86.4, 972 + 21.6 = 993.6,
        // 162 - 21.6 = 140.4 and 648 + 21.6 = 669.6.
        val zones = listOf(zone(0.1f, 0.1f, 0.495f, 0.4f), zone(0.505f, 0.1f, 0.9f, 0.4f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(86.4f, 140.4f, 540f, 669.6f))
        assertSame(second, Rect(540f, 140.4f, 993.6f, 669.6f))
        assertThat(first.right).isAtMost(second.left)
    }

    @Test
    fun `two zones a wide gutter apart keep the whole margin`() {
        // The first ends at 0.47 x 1080 = 507.6, the second begins at 0.53 x 1080 = 572.4: a gutter of 64.8, 6 % of
        // the width, half of which, 32.4, is over the margin. 507.6 + 21.6 = 529.2 and 572.4 - 21.6 = 550.8.
        val zones = listOf(zone(0.1f, 0.1f, 0.47f, 0.4f), zone(0.53f, 0.1f, 0.9f, 0.4f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(86.4f, 140.4f, 529.2f, 669.6f))
        assertSame(second, Rect(550.8f, 140.4f, 993.6f, 669.6f))
    }

    @Test
    fun `beside a thin gutter, the side at the edge of the page keeps its margin, clipped to the room there`() {
        // The first begins at 0.01 x 1080 = 10.8: its margin of 21.6 is clipped to the 10.8 the page leaves, at 0.
        // The second ends at the edge, 1080. Between them the gutter of 10.8 is shared, at 540.
        val zones = listOf(zone(0.01f, 0.1f, 0.495f, 0.4f), zone(0.505f, 0.1f, 1f, 0.4f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(0f, 140.4f, 540f, 669.6f))
        assertSame(second, Rect(540f, 140.4f, 1080f, 669.6f))
    }

    @Test
    fun `a zone below another a thin gutter apart stops at its middle too`() {
        // On the page 1620 high, the first ends at 0.395 x 1620 = 639.9 and the second begins at 0.405 x 1620 =
        // 656.1: a gutter of 16.2, under twice the margin, whose middle is at 648. The second ends at
        // 0.7 x 1620 + 21.6 = 1155.6; both go from 108 - 21.6 = 86.4 to 540 + 21.6 = 561.6.
        val zones = listOf(zone(0.1f, 0.1f, 0.5f, 0.395f), zone(0.1f, 0.405f, 0.5f, 0.7f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(86.4f, 140.4f, 561.6f, 648f))
        assertSame(second, Rect(86.4f, 648f, 561.6f, 1155.6f))
        assertThat(first.bottom).isAtMost(second.top)
    }

    @Test
    fun `a zone that is not across from another does not limit its margin`() {
        // The second is right of the first and below it: from 545.4 and from 0.41 x 1620 = 664.2, when the first
        // ends at 534.6 and at 648. Neither is beside the other nor under it: both keep the whole margin, the
        // first to 534.6 + 21.6 = 556.2 and 669.6, the second from 545.4 - 21.6 = 523.8 and 664.2 - 21.6 = 642.6.
        val zones = listOf(zone(0.1f, 0.1f, 0.495f, 0.4f), zone(0.505f, 0.41f, 0.9f, 0.7f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(86.4f, 140.4f, 556.2f, 669.6f))
        assertSame(second, Rect(523.8f, 642.6f, 993.6f, 1155.6f))
    }

    @Test
    fun `zones that overlap have no margin on the side of the other`() {
        // The first ends at 540, the second begins at 0.48 x 1080 = 518.4, inside the first: neither is widened
        // towards the other.
        val zones = listOf(zone(0.1f, 0.1f, 0.5f, 0.4f), zone(0.48f, 0.1f, 0.9f, 0.4f))

        val (first, second) = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(first, Rect(86.4f, 140.4f, 540f, 669.6f))
        assertSame(second, Rect(518.4f, 140.4f, 993.6f, 669.6f))
    }

    @Test
    fun `a zone is walked on what its margins are beside its neighbours`() {
        // The row ends at 972 and the zone under it begins at 0.61 x 1620 = 988.2: a gutter of 16.2, whose middle
        // is at 980.1. The row is walked as it is alone, in three stops 540 wide and 270 apart, which end at 980.1
        // and not at 972 + 21.6 = 993.6. The zone under it, from 980.1 to 0.8 x 1620 + 21.6 = 1317.6, is walked
        // the same way.
        val stops = pageStops(listOf(row, zone(0f, 0.61f, 1f, 0.8f)), page, phone, Direction.LeftToRight)

        assertThat(stops).hasSize(6)
        assertSame(stops[0], Rect(0f, 626.4f, 540f, 980.1f))
        assertSame(stops[1], Rect(270f, 626.4f, 810f, 980.1f))
        assertSame(stops[2], Rect(540f, 626.4f, 1080f, 980.1f))
        assertSame(stops[3], Rect(0f, 980.1f, 540f, 1317.6f))
    }

    @Test
    fun `a zone is where its page is in the spread, and stays in its room`() {
        val second = Rect(800f, 100f, 1600f, 1300f)

        val stop = pageStops(listOf(zone(0f, 0f, 0.5f, 0.5f)), second, tablet, Direction.LeftToRight).single()

        assertSame(stop, Rect(800f, 100f, 1216f, 716f))
    }

    @Test
    fun `on a phone held upright a row as wide as the page is walked in three stops from the left`() {
        // The row with its margin: 1080 wide, from 648 - 21.6 = 626.4 to 972 + 21.6 = 993.6, so 367.2 high.
        // Fitted it is at 1080 / 1080 = 1, the zoom of the page: too large (under 1.5). Its height would fit at
        // 2340 / 367.2 = 6.37, capped at twice the fitted zoom: 2, a gain of 2. At 2 the screen shows 1080 / 2 = 540.
        // 1 + ceil((1080 - 540) / (540 x 0.75)) = 1 + ceil(1.33) = 3 stops, (1080 - 540) / 2 = 270 apart: each
        // shares 540 - 270 = 270 with the next, half its length.
        val stops = stops(row)

        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(0f, 626.4f, 540f, 993.6f))
        assertSame(stops[1], Rect(270f, 626.4f, 810f, 993.6f))
        assertSame(stops[2], Rect(540f, 626.4f, 1080f, 993.6f))
        stops.zipWithNext { one, next -> assertThat(one.right - next.left).isAtLeast(StopOverlap * one.width) }
        stops.forEach { assertThat(stopPlace(it, phone).zoom).isWithin(Near).of(2f) }
    }

    @Test
    fun `reading from the right, the row is walked in the same three stops from the right`() {
        // The same arithmetic, counted from the right edge: the first stop ends at 1080, the last begins at 0.
        val stops = stops(row, Direction.RightToLeft)

        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(540f, 626.4f, 1080f, 993.6f))
        assertSame(stops[1], Rect(270f, 626.4f, 810f, 993.6f))
        assertSame(stops[2], Rect(0f, 626.4f, 540f, 993.6f))
        stops.zipWithNext { one, next -> assertThat(next.right - one.left).isAtLeast(StopOverlap * one.width) }
    }

    @Test
    fun `on a tablet held sideways the same row fits readable, and is one stop`() {
        // The margin is 1067 x 0.02 = 21.34: the row is 1067 wide, from 640 - 21.34 to 960 + 21.34, 362.68 high.
        // Fitted it is at min(2560 / 1067, 1600 / 362.68) = 2.40, the page at min(2.40, 1600 / 1600) = 1:
        // 2.40 is not under 1.5, the row is not too large.
        val stop = pageStops(listOf(row), tabletPage, tablet, Direction.LeftToRight).single()

        assertSame(stop, Rect(0f, 618.66f, 1067f, 981.34f))
    }

    @Test
    fun `a zone that is the full page is one stop, walking it would hardly show it larger`() {
        // The page fitted is at 1. Its height would fit at 2340 / 1620 = 1.44: a gain of 1.44, under 1.75.
        assertThat(stops(all)).containsExactly(page)

        // A page nearly as high as the screen: 2340 / 2200 = 1.064. It was walked in [0, 1015] and [65, 1080].
        val high = Rect(0f, 0f, 1080f, 2200f)
        assertThat(pageStops(listOf(all), high, phone, Direction.LeftToRight)).containsExactly(high)

        // A page of the shape of the screen: no stop shows it larger, a gain of 1.
        val full = Rect(0f, 0f, 1080f, 2340f)
        assertThat(pageStops(listOf(all), full, phone, Direction.LeftToRight)).containsExactly(full)
    }

    @Test
    fun `a zone nine tenths of the height of the page is one stop`() {
        // From the top of the page to 1620 x 0.9 + 21.6 = 1479.6. Fitted at 1; its height would fit at
        // 2340 / 1479.6 = 1.58: a gain of 1.58, under 1.75.
        assertSame(stops(zone(0f, 0f, 1f, 0.9f)).single(), Rect(0f, 0f, 1080f, 1479.6f))
    }

    @Test
    fun `half the page is walked in three stops, at the zoom the cap allows`() {
        // From the top of the page to 810 + 21.6 = 831.6. Fitted at 1; its height would fit at 2340 / 831.6 = 2.81,
        // capped at 2: a gain of 2. Stops 1080 / 2 = 540 wide, 1 + ceil(540 / 405) = 3 of them, 270 apart.
        val stops = stops(zone(0f, 0f, 1f, 0.5f))

        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(0f, 0f, 540f, 831.6f))
        assertSame(stops[1], Rect(270f, 0f, 810f, 831.6f))
        assertSame(stops[2], Rect(540f, 0f, 1080f, 831.6f))
        // Capped: shown at 2, when their height alone would let them be at 2.81.
        stops.forEach { assertThat(stopPlace(it, phone).zoom).isWithin(Near).of(2f) }
    }

    @Test
    fun `three quarters of the page are walked at the zoom that fits their height, under the cap`() {
        // From the top of the page to 1215 + 21.6 = 1236.6. Fitted at 1; its height fits at 2340 / 1236.6 = 1.892,
        // under the cap of 2: a gain of 1.892, over 1.75. Stops 1080 / 1.892 = 570.74 wide,
        // 1 + ceil((1080 - 570.74) / (570.74 x 0.75)) = 1 + ceil(1.19) = 3 of them, 509.26 / 2 = 254.63 apart.
        val stops = stops(zone(0f, 0f, 1f, 0.75f))

        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(0f, 0f, 570.74f, 1236.6f))
        assertSame(stops[1], Rect(254.63f, 0f, 825.37f, 1236.6f))
        assertSame(stops[2], Rect(509.26f, 0f, 1080f, 1236.6f))
        stops.forEach { assertThat(stopPlace(it, phone).zoom).isWithin(Near).of(2340f / 1236.6f) }
    }

    @Test
    fun `a tall zone is walked in three stops from its top, whatever the direction`() {
        // A phone held sideways and a page as high as it. The margin is 720 x 0.02 = 14.4: the zone goes from
        // 240 - 14.4 = 225.6 to 480 + 14.4 = 494.4, 268.8 wide, and is 1080 high. Fitted at
        // min(2340 / 268.8, 1080 / 1080) = 1, the zoom of the page: too large, and the height limits. Its width
        // would fit at 2340 / 268.8 = 8.7, capped at 2: a gain of 2. Stops 1080 / 2 = 540 high,
        // 1 + ceil(540 / 405) = 3 of them, 270 apart.
        val sideways = Size(2340f, 1080f)
        val room = Rect(0f, 0f, 720f, 1080f)
        val tall = zone(1f / 3, 0f, 2f / 3, 1f)

        val stops = pageStops(listOf(tall), room, sideways, Direction.LeftToRight)

        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(225.6f, 0f, 494.4f, 540f))
        assertSame(stops[1], Rect(225.6f, 270f, 494.4f, 810f))
        assertSame(stops[2], Rect(225.6f, 540f, 494.4f, 1080f))
        assertThat(pageStops(listOf(tall), room, sideways, Direction.RightToLeft)).isEqualTo(stops)
    }

    @Test
    fun `a zone walked on the second page of a spread stays in the room of that page`() {
        // A tablet held upright, 1600 x 2560, two pages of 800 x 1200 side by side: the second from 800 to 1600.
        // The margin is 16: the row is 800 wide, from 480 - 16 = 464 to 720 + 16 = 736, 272 high. Fitted at
        // min(1600 / 800, 2560 / 272) = 2, the zoom of the page: too large. Its height would fit at 9.4, capped at
        // 4: a gain of 2. Stops 1600 / 4 = 400 wide, 1 + ceil((800 - 400) / 300) = 3 of them, 200 apart.
        val upright = Size(1600f, 2560f)
        val second = Rect(800f, 0f, 1600f, 1200f)

        val fromLeft = pageStops(listOf(row), second, upright, Direction.LeftToRight)
        val fromRight = pageStops(listOf(row), second, upright, Direction.RightToLeft)

        assertThat(fromLeft).hasSize(3)
        assertSame(fromLeft[0], Rect(800f, 464f, 1200f, 736f))
        assertSame(fromLeft[1], Rect(1000f, 464f, 1400f, 736f))
        assertSame(fromLeft[2], Rect(1200f, 464f, 1600f, 736f))
        assertThat(fromRight).hasSize(3)
        fromRight.zip(fromLeft.reversed()).forEach { (one, other) -> assertSame(one, other) }
    }

    @Test
    fun `when large zones are not walked, every zone is one stop`() {
        val zones = listOf(row, zone(0f, 0f, 1f, 0.5f), all)

        val stops = pageStops(zones, page, phone, Direction.LeftToRight, walkLarge = false)

        // The three overlap one another, above and below: none has a margin there. The row is from 648 to 972,
        // the half page ends at 810.
        assertThat(stops).hasSize(3)
        assertSame(stops[0], Rect(0f, 648f, 1080f, 972f))
        assertSame(stops[1], Rect(0f, 0f, 1080f, 810f))
        assertSame(stops[2], page)
    }

    @Test
    fun `a screen with no size walks nothing`() {
        assertThat(pageStops(listOf(row), page, Size.Zero, Direction.LeftToRight)).hasSize(1)
        assertThat(pageStops(listOf(row), page, Size(Float.NaN, 2340f), Direction.LeftToRight)).hasSize(1)
    }

    @Test
    fun `a zone that is empty, outside its page or not a number gives no stop`() {
        val good = zone(0.1f, 0.1f, 0.6f, 0.4f)
        val zones = listOf(
            zone(1.2f, 0f, 1.5f, 1f),
            zone(0f, -0.5f, 1f, -0.2f),
            zone(Float.NaN, 0f, 1f, 1f),
            zone(0f, 0f, 1f, Float.POSITIVE_INFINITY),
            zone(0.5f, 0.2f, 0.5f, 0.8f),
            zone(0.6f, 0.2f, 0.4f, 0.8f),
            good,
        )

        val stops = pageStops(zones, page, phone, Direction.LeftToRight)

        assertSame(stops.single(), Rect(108f - margin, 162f - margin, 648f + margin, 648f + margin))
        assertThat(pageStops(listOf(good), Rect.Zero, phone, Direction.LeftToRight)).isEmpty()
        assertThat(pageStops(listOf(good), Rect(0f, 0f, Float.NaN, 1620f), phone, Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `two pages side by side give the stops of the first page read, then those of the second`() {
        val left = Rect(0f, 0f, 1066f, 1600f)
        val right = Rect(1066f, 0f, 2132f, 1600f)
        val two = listOf(zone(0.05f, 0.05f, 0.95f, 0.45f), zone(0.05f, 0.55f, 0.95f, 0.95f))
        val three = two + zone(0.3f, 0.46f, 0.7f, 0.54f)

        val fromLeft = spreadStops(
            listOf(StopPage(left, PageZones.Cut(two, 0)), StopPage(right, PageZones.Cut(three, 0))),
            tablet,
            Direction.LeftToRight,
        )
        val fromRight = spreadStops(
            listOf(StopPage(right, PageZones.Cut(three, 0)), StopPage(left, PageZones.Cut(two, 0))),
            tablet,
            Direction.RightToLeft,
        )

        assertThat(fromLeft).hasSize(5)
        val ofLeft = pageStops(two, left, tablet, Direction.LeftToRight)
        assertThat(fromLeft).isEqualTo(ofLeft + pageStops(three, right, tablet, Direction.LeftToRight))
        fromLeft!!.take(2).forEach { assertThat(it.right).isAtMost(left.right) }
        fromLeft.drop(2).forEach { assertThat(it.left).isAtLeast(right.left) }
        assertThat(fromRight).hasSize(5)
        fromRight!!.take(3).forEach { assertThat(it.left).isAtLeast(right.left) }
        fromRight.drop(3).forEach { assertThat(it.right).isAtMost(left.right) }
    }

    @Test
    fun `a spread with a page not known gives no answer yet`() {
        val cut = PageZones.Cut(listOf(zone(0f, 0f, 1f, 0.5f), zone(0f, 0.5f, 1f, 1f)), 0)
        val left = Rect(0f, 0f, 1066f, 1600f)
        val right = Rect(1066f, 0f, 2132f, 1600f)

        assertThat(spreadStops(listOf(StopPage(page, PageZones.Unknown)), phone, Direction.LeftToRight)).isNull()
        assertThat(
            spreadStops(listOf(StopPage(left, cut), StopPage(right, PageZones.Unknown)), tablet, Direction.LeftToRight),
        ).isNull()
    }

    @Test
    fun `a spread of whole pages has no stop`() {
        val whole = WholePage.entries.map { PageZones.Whole(it) }

        assertThat(spreadStops(whole.map { StopPage(page, it) }, phone, Direction.LeftToRight)).isEmpty()
        assertThat(spreadStops(emptyList(), phone, Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `a whole page beside a cut one is one stop, its room, in its place in reading order`() {
        val cut = PageZones.Cut(listOf(zone(0.1f, 0.1f, 0.9f, 0.45f), zone(0.1f, 0.55f, 0.9f, 0.9f)), 0)
        val whole = PageZones.Whole(WholePage.NoPanels)
        val left = Rect(0f, 0f, 1066f, 1600f)
        val right = Rect(1066f, 0f, 2132f, 1600f)

        val wholeFirst = spreadStops(listOf(StopPage(left, whole), StopPage(right, cut)), tablet, Direction.LeftToRight)
        val wholeLast = spreadStops(listOf(StopPage(right, cut), StopPage(left, whole)), tablet, Direction.RightToLeft)

        assertThat(wholeFirst).isEqualTo(listOf(left) + pageStops(cut.zones, right, tablet, Direction.LeftToRight))
        assertThat(wholeFirst).hasSize(3)
        assertThat(wholeLast).isEqualTo(pageStops(cut.zones, right, tablet, Direction.RightToLeft) + left)
        assertThat(wholeLast).hasSize(3)
    }

    @Test
    fun `a whole page with no room beside a cut one gives no stop`() {
        val cut = PageZones.Cut(listOf(zone(0.1f, 0.1f, 0.9f, 0.45f), zone(0.1f, 0.55f, 0.9f, 0.9f)), 0)
        val whole = PageZones.Whole(WholePage.Failed)
        val right = Rect(1066f, 0f, 2132f, 1600f)
        val ofCut = pageStops(cut.zones, right, tablet, Direction.LeftToRight)

        listOf(Rect.Zero, Rect(0f, 0f, Float.NaN, 1600f), Rect(0f, 0f, Float.POSITIVE_INFINITY, 1600f)).forEach {
            val stops = spreadStops(listOf(StopPage(it, whole), StopPage(right, cut)), tablet, Direction.LeftToRight)
            assertThat(stops).isEqualTo(ofCut)
        }
    }

    @Test
    fun `a stop is placed fitted, in the middle of the screen`() {
        val place = stopPlace(Rect(100f, 200f, 640f, 740f), phone)

        assertThat(place.zoom).isWithin(Near).of(2f)
        // Its centre, (370, 470), is at the centre of the screen, (540, 1170).
        assertThat(place.offset.x).isWithin(Near).of(-200f)
        assertThat(place.offset.y).isWithin(Near).of(230f)
    }

    @Test
    fun `a stop is not kept inside what a finger could reach`() {
        // The last column of a page as wide as the screen: at its zoom the page's right edge is left of the screen's.
        val place = stopPlace(Rect(810f, 0f, 1080f, 1620f), phone)

        assertThat(place.zoom).isWithin(Near).of(2340f / 1620f)
        assertThat(1080f * place.zoom + place.offset.x).isLessThan(phone.width)
    }

    @Test
    fun `the zoom of a stop is never past the highest`() {
        val small = stopPlace(Rect(500f, 500f, 550f, 550f), phone)

        assertThat(small.zoom).isEqualTo(MaxZoom)
        assertThat(small.offset).isEqualTo(Offset(540f - 525f * MaxZoom, 1170f - 525f * MaxZoom))
    }

    @Test
    fun `a stop larger than the screen is shown whole, under the zoom of the fit`() {
        // A page fitted to the width of a phone held sideways is higher than the screen, and so is a panel of it.
        // The stop is 1217 x 1216: it fits at min(2340 / 1217, 1080 / 1216) = 0.888. Its centre, (708.5, 808), goes
        // to the centre of the screen, (1170, 540): 1170 - 708.5 x 0.888 = 540.74, 540 - 808 x 0.888 = -177.63.
        val sideways = Size(2340f, 1080f)
        val stop = Rect(100f, 200f, 1317f, 1416f)

        val place = stopPlace(stop, sideways)

        assertThat(place.zoom).isWithin(0.0001f).of(0.88816f)
        assertThat(place.offset.x).isWithin(Near).of(540.74f)
        assertThat(place.offset.y).isWithin(Near).of(-177.63f)
        // Whole: its top and bottom at those of the screen, as far from the left as from the right.
        assertThat(stop.top * place.zoom + place.offset.y).isWithin(Near).of(0f)
        assertThat(stop.bottom * place.zoom + place.offset.y).isWithin(Near).of(1080f)
        assertThat(stop.left * place.zoom + place.offset.x).isWithin(Near).of(629.56f)
        assertThat(stop.right * place.zoom + place.offset.x).isWithin(Near).of(2340f - 629.56f)
    }

    @Test
    fun `a stop or a screen with no size is placed at the fit, where the spread is`() {
        val nowhere = StopPlace(1f, Offset.Zero)
        val stop = Rect(100f, 200f, 640f, 740f)

        assertThat(stopPlace(Rect.Zero, phone)).isEqualTo(nowhere)
        assertThat(stopPlace(Rect(300f, 200f, 100f, 740f), phone)).isEqualTo(nowhere)
        assertThat(stopPlace(Rect(Float.NaN, 200f, 640f, 740f), phone)).isEqualTo(nowhere)
        assertThat(stopPlace(Rect(100f, 200f, Float.POSITIVE_INFINITY, 740f), phone)).isEqualTo(nowhere)
        assertThat(stopPlace(stop, Size.Zero)).isEqualTo(nowhere)
        assertThat(stopPlace(stop, Size(1080f, 0f))).isEqualTo(nowhere)
        assertThat(stopPlace(stop, Size(Float.NaN, 2340f))).isEqualTo(nowhere)
    }
}
