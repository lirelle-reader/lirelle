package app.tfdn.lirelle.feature.comics

import androidx.lifecycle.ViewModelStore
import app.tfdn.lirelle.core.comics.panels.PageZones
import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

private val Filled = Zone(0.1f, 0.1f, 0.9f, 0.4f, ZoneKind.Filled)
private val Sparse = Zone(0.1f, 0.5f, 0.9f, 0.9f, ZoneKind.Sparse)

private val Series = DirectionScope.Series(SeriesId("4"))

/**
 * Reading panel by panel, in the reader: what the panels are told, what the state says of the mode and of the zones
 * of the pages near, for the mode and for the zones drawn in a build that can be debugged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PanelModeTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the album's panels are opened with its pages`() = runTest {
        val harness = ComicHarness(this)
        harness.reader()

        assertThat(harness.panelsOpener.opened).isEqualTo(harness.opener.opened)
        assertThat(harness.panelsOpener.opened).hasSize(1)
        assertThat(harness.panels.pages).isSameInstanceAs(harness.pages)
    }

    @Test
    fun `the switch turns the cutting on, then off`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        assertThat(reader.state.value.zonesShown).isFalse()

        reader.showZones(true)
        assertThat(reader.state.value.zonesShown).isTrue()
        assertThat(harness.cuttings()).containsExactly("cutting true")

        reader.showZones(false)
        assertThat(reader.state.value.zonesShown).isFalse()
        assertThat(harness.cuttings()).containsExactly("cutting true", "cutting false").inOrder()
    }

    @Test
    fun `the zones of the page shown reach the state as they are known`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.showZones(true)
        assertThat(reader.state.value.panels.zones).isEmpty()

        harness.panels.cut(0, listOf(Filled, Sparse), millis = 120)

        assertThat(reader.state.value.panels.zones).containsExactly(0, PageZones.Cut(listOf(Filled, Sparse), 120))
    }

    @Test
    fun `zones known before the switch is turned on are shown with it`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.panels.cut(0, listOf(Filled, Sparse))
        assertThat(reader.state.value.panels.zones).isEmpty()

        reader.showZones(true)

        assertThat(reader.state.value.panels.zones.keys).containsExactly(0)
    }

    @Test
    fun `a page no longer near is dropped, and no longer watched`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.showZones(true)
        harness.panels.cut(0, listOf(Filled, Sparse))
        harness.panels.cut(2, listOf(Sparse, Filled))

        reader.next()
        reader.next()

        assertThat(reader.state.value.panels.zones).containsExactly(2, PageZones.Cut(listOf(Sparse, Filled), 0))
        assertThat(harness.panels.watchers(0)).isEqualTo(0)
        assertThat(harness.panels.watchers(2)).isEqualTo(1)
    }

    @Test
    fun `the zones of the spreads beside the one shown are in the state, those further are not`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.setPanelMode(true)
        reader.goTo(10)

        (8..12).forEach { harness.panels.cut(it, listOf(Filled, Sparse)) }
        assertThat(reader.state.value.panels.zones.keys).containsExactly(9, 10, 11)

        reader.next()
        assertThat(reader.state.value.panels.zones.keys).containsExactly(10, 11, 12)
        assertThat(harness.panels.watchers(9)).isEqualTo(0)
    }

    @Test
    fun `beside a spread of two are the two pages of the spread before and of the one after`() = runTest {
        val two = InMemoryComicSettingsStore(ComicSettings(twoPages = TwoPages.Always), panels = setOf(Series))
        val harness = ComicHarness(this, settings = two)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.goTo(5)

        (0 until 12).forEach { harness.panels.cut(it, listOf(Filled, Sparse)) }

        assertThat(reader.state.value.panels.zones.keys).containsExactly(3, 4, 5, 6, 7, 8)
    }

    @Test
    fun `both pages of a spread of two have their zones`() = runTest {
        val two = InMemoryComicSettingsStore(initial = ComicSettings(twoPages = TwoPages.Always))
        val harness = ComicHarness(this, settings = two)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)
        reader.showZones(true)
        reader.next()

        harness.panels.cut(1, listOf(Filled, Sparse))
        harness.panels.cut(2, listOf(Sparse, Filled))
        harness.panels.cut(5, listOf(Filled, Sparse))

        assertThat(reader.state.value.panels.zones.keys).containsExactly(1, 2)
    }

    @Test
    fun `zones that are no longer known are no longer shown`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.showZones(true)
        harness.panels.cut(0, listOf(Filled, Sparse))

        harness.panels.forget(0)

        assertThat(reader.state.value.panels.zones).isEmpty()
    }

    @Test
    fun `turned off, nothing is shown nor watched`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.showZones(true)
        harness.panels.cut(0, listOf(Filled, Sparse))

        reader.showZones(false)

        assertThat(reader.state.value.panels.zones).isEmpty()
        assertThat(harness.panels.watchers(0)).isEqualTo(0)
    }

    @Test
    fun `the panels are told the page shown, at opening and at each turn, with the page count`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()
        assertThat(harness.shows()).containsExactly("show 11 $Pages")

        reader.next()
        reader.goTo(30)
        reader.previous()

        assertThat(harness.shows())
            .containsExactly("show 11 $Pages", "show 12 $Pages", "show 30 $Pages", "show 29 $Pages").inOrder()
    }

    @Test
    fun `the reader watches a page before the panels are told it is shown`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.next()
        reader.goTo(30)

        assertThat(harness.panels.watchedWhenShown).containsExactly(true, true, true)
    }

    @Test
    fun `the panels are told the direction at opening, and when it changes`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        assertThat(harness.directions()).containsExactly("direction LeftToRight")

        reader.setDirection(Direction.RightToLeft)

        assertThat(harness.directions()).containsExactly("direction LeftToRight", "direction RightToLeft").inOrder()
    }

    @Test
    fun `an album read right to left says so before any page is told shown`() = runTest {
        val kept = InMemoryComicSettingsStore(
            directions = mapOf(Series to Direction.RightToLeft),
        )
        val harness = ComicHarness(this, settings = kept)
        harness.reader()

        assertThat(harness.panels.calls).containsExactly("direction RightToLeft", "show 0 $Pages").inOrder()
    }

    @Test
    fun `a reader cleared closes the panels`() = runTest {
        val harness = ComicHarness(this)
        val owner = ViewModelStore().apply { put("reader", harness.reader()) }
        assertThat(harness.panels.closed).isFalse()

        owner.clear()

        assertThat(harness.panels.closed).isTrue()
    }

    @Test
    fun `the mode off and the zones not shown, nothing is cut, watched nor collected`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val opened = reader.state.value.panels
        harness.panels.cut(0, listOf(Filled, Sparse))
        harness.panels.cut(1, listOf(Filled, Sparse))

        reader.next()
        reader.setDirection(Direction.RightToLeft)
        reader.goTo(30)
        reader.previous()

        assertThat(harness.cuttings()).isEmpty()
        assertThat(reader.state.value.zonesShown).isFalse()
        assertThat(opened).isEqualTo(PanelReading(offered = true))
        // The very same: the turns wrote nothing of the panels in the state.
        assertThat(reader.state.value.panels).isSameInstanceAs(opened)
        (0 until Pages).forEach { assertThat(harness.panels.watchers(it)).isEqualTo(0) }
    }

    @Test
    fun `turning the mode on writes it for the series, and the pages are cut from then on`() = runTest {
        val kept = InMemoryComicSettingsStore(noticeSeen = true)
        val harness = ComicHarness(this, settings = kept)
        val reader = harness.reader()
        assertThat(reader.state.value.panels.on).isFalse()

        reader.setPanelMode(true)

        assertThat(kept.panels).containsExactly(Series)
        assertThat(reader.state.value.panels.on).isTrue()
        assertThat(harness.cuttings()).containsExactly("cutting true")

        reader.next()
        reader.setPanelMode(true)
        assertThat(harness.cuttings()).containsExactly("cutting true")
    }

    @Test
    fun `the mode on, the zones of the pages near reach the state though they are not drawn`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.panels.cut(1, listOf(Filled, Sparse))

        reader.setPanelMode(true)
        harness.panels.whole(0, WholePage.Cover)

        assertThat(reader.state.value.zonesShown).isFalse()
        assertThat(reader.state.value.panels.zones)
            .containsExactly(0, PageZones.Whole(WholePage.Cover), 1, PageZones.Cut(listOf(Filled, Sparse), 0))
    }

    @Test
    fun `an album of a series already read panel by panel opens in the mode, and its pages are cut`() = runTest {
        val harness = ComicHarness(this, settings = InMemoryComicSettingsStore(panels = setOf(Series)))
        val reader = harness.reader()

        assertThat(reader.state.value.panels.on).isTrue()
        assertThat(harness.cuttings()).containsExactly("cutting true")
        assertThat(harness.panels.watchers(0)).isEqualTo(1)
        assertThat(harness.panels.watchers(1)).isEqualTo(1)
    }

    @Test
    fun `the mode of another series is not this album's`() = runTest {
        val other = InMemoryComicSettingsStore(panels = setOf(DirectionScope.Series(SeriesId("5"))))
        val harness = ComicHarness(this, settings = other)
        val reader = harness.reader()

        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(harness.cuttings()).isEmpty()
    }

    @Test
    fun `turning the mode off stops the cutting, and nothing is known nor watched any more`() = runTest {
        val kept = InMemoryComicSettingsStore(panels = setOf(Series))
        val harness = ComicHarness(this, settings = kept)
        val reader = harness.reader()
        harness.panels.cut(0, listOf(Filled, Sparse))
        assertThat(reader.state.value.panels.zones).isNotEmpty()

        reader.setPanelMode(false)

        assertThat(kept.panels).isEmpty()
        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(harness.cuttings()).containsExactly("cutting true", "cutting false").inOrder()
        assertThat(reader.state.value.panels.zones).isEmpty()
        assertThat(harness.panels.watchers(0)).isEqualTo(0)
    }

    @Test
    fun `the mode turned off while the zones are shown, the pages are still cut`() = runTest {
        val harness = ComicHarness(this, settings = InMemoryComicSettingsStore(panels = setOf(Series)))
        val reader = harness.reader()
        reader.showZones(true)
        harness.panels.cut(0, listOf(Filled, Sparse))

        reader.setPanelMode(false)

        assertThat(harness.cuttings()).containsExactly("cutting true")
        assertThat(reader.state.value.panels.zones.keys).containsExactly(0)

        reader.showZones(false)
        assertThat(harness.cuttings()).containsExactly("cutting true", "cutting false").inOrder()
    }

    @Test
    fun `the zones no longer shown while the mode is on, the pages are still cut`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.showZones(true)
        reader.setPanelMode(true)
        harness.panels.cut(0, listOf(Filled, Sparse))

        reader.showZones(false)

        assertThat(harness.cuttings()).containsExactly("cutting true")
        assertThat(reader.state.value.panels.zones.keys).containsExactly(0)
    }

    @Test
    fun `the panels know how the album is read and where the reader is before they are told to cut`() = runTest {
        val kept =
            InMemoryComicSettingsStore(directions = mapOf(Series to Direction.RightToLeft), panels = setOf(Series))
        val harness = ComicHarness(this, settings = kept)
        harness.reader()

        assertThat(harness.panels.calls)
            .containsExactly("direction RightToLeft", "show 0 $Pages", "cutting true").inOrder()
    }

    @Test
    fun `an album whose pages are never cut is not offered the mode, and is not in it`() = runTest {
        val harness = ComicHarness(this, settings = InMemoryComicSettingsStore(panels = setOf(Series)))
        harness.panels.cuts = false
        val reader = harness.reader()

        assertThat(reader.state.value.panels.offered).isFalse()
        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(harness.cuttings()).isEmpty()
    }

    @Test
    fun `an album that can be cut is offered the mode once it is open`() = runTest {
        val harness = ComicHarness(this)
        harness.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val reader = harness.reader()
        assertThat(reader.state.value.panels.offered).isFalse()

        harness.library.bookResult = ApiResult.Success(sampleComicDetail())
        reader.retryOpening()

        assertThat(reader.state.value.panels.offered).isTrue()
    }

    @Test
    fun `whether the whole page comes first follows the device's setting`() = runTest {
        val kept = InMemoryComicSettingsStore()
        val harness = ComicHarness(this, settings = kept)
        val reader = harness.reader()
        assertThat(reader.state.value.panels.wholePageFirst).isTrue()

        kept.setWholePageFirst(false)
        assertThat(reader.state.value.panels.wholePageFirst).isFalse()

        val second = ComicHarness(this, settings = InMemoryComicSettingsStore(ComicSettings(wholePageFirst = false)))
        assertThat(second.reader().state.value.panels.wholePageFirst).isFalse()
    }

    @Test
    fun `the bars stay shown while the user chooses the mode`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.setPanelMode(true)
        advanceTimeBy(harness.timings.bars + 10.seconds)

        assertThat(reader.state.value.barsShown).isTrue()
    }

    @Test
    fun `in a build that cannot be debugged the cuts are not logged`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.setPanelMode(true)

        harness.panels.cut(0, listOf(Filled, Sparse), millis = 80)
        harness.panels.whole(1, WholePage.Cover)

        assertThat(reader.state.value.panels.zones.keys).containsExactly(0, 1)
        assertThat(ShadowLog.getLogsForTag("Panels")).isEmpty()
    }

    @Test
    fun `in a build that can be debugged the cuts are logged while the mode is on as while the zones are shown`() =
        runTest {
            val harness = ComicHarness(this)
            val reader = harness.reader()
            reader.buildDebuggable(true)
            reader.setPanelMode(true)

            harness.panels.cut(0, listOf(Filled, Sparse), millis = 80)

            assertThat(ShadowLog.getLogsForTag("Panels").map { it.msg }).containsExactly("page 0: 2 zones in 80 ms")
        }

    @Test
    fun `a build said to be debugged after a cut logs the cuts that follow`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.setPanelMode(true)
        harness.panels.cut(0, listOf(Filled, Sparse), millis = 80)

        reader.buildDebuggable(true)
        harness.panels.cut(1, listOf(Filled, Sparse), millis = 90)

        assertThat(ShadowLog.getLogsForTag("Panels").map { it.msg }).containsExactly("page 1: 2 zones in 90 ms")
    }

    @Test
    fun `each cut shown is logged once, with its page from 0 and its time`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.buildDebuggable(true)
        reader.showZones(true)

        harness.panels.cut(0, listOf(Filled, Sparse), millis = 120)
        // Back on the page, its zones are shown again: they are the same cut.
        reader.next()
        reader.previous()

        assertThat(ShadowLog.getLogsForTag("Panels").map { it.msg }).containsExactly("page 0: 2 zones in 120 ms")
    }

    @Test
    fun `a page read whole reaches the state with why, and is logged once`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.buildDebuggable(true)
        reader.showZones(true)

        harness.panels.whole(0, WholePage.Cover)
        harness.panels.whole(1, WholePage.Failed)
        val both = mapOf(0 to PageZones.Whole(WholePage.Cover), 1 to PageZones.Whole(WholePage.Failed))
        assertThat(reader.state.value.panels.zones).isEqualTo(both)

        reader.next()
        reader.previous()

        assertThat(reader.state.value.panels.zones).isEqualTo(both)
        assertThat(ShadowLog.getLogsForTag("Panels").map { it.msg })
            .containsExactly("page 0: whole (cover)", "page 1: whole (failed)").inOrder()
    }

    private fun ComicHarness.cuttings() = panels.calls.filter { it.startsWith("cutting") }

    private fun ComicHarness.shows() = panels.calls.filter { it.startsWith("show") }

    private fun ComicHarness.directions() = panels.calls.filter { it.startsWith("direction") }
}
