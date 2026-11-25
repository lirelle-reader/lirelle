package app.tfdn.lirelle.feature.comics

import androidx.lifecycle.ViewModelStore
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private val Series = DirectionScope.Series(SeriesId("4"))

private val vertical = mapOf<DirectionScope, Direction>(Series to Direction.Vertical)

/** A reader whose album is of a series read vertically. */
private fun TestScope.verticalHarness() =
    ComicHarness(this, settings = InMemoryComicSettingsStore(directions = vertical, noticeSeen = true))

/**
 * An album read vertically: what the strip of its pages tells the reader as it scrolls, and what the reader tells
 * the pages and the session of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderStripTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `read vertically, each page is a spread of its own, whatever the window`() = runTest {
        val two = InMemoryComicSettingsStore(ComicSettings(twoPages = TwoPages.Always), directions = vertical)
        val reader = ComicHarness(this, settings = two).reader()

        reader.windowSize(widthDp = 1280, heightDp = 800)

        val state = reader.state.value
        assertThat(state.vertical).isTrue()
        assertThat(state.twoPages).isFalse()
        assertThat(state.spreads).hasSize(Pages)
    }

    @Test
    fun `the page at the top is the page read, and it is asked for at once`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 5, last = 7)

        assertThat(reader.state.value.spread).isEqualTo(Spread(5))
        assertThat(reader.state.value.label).isEqualTo(PageLabel(first = 6, last = null, count = Pages))
        assertThat(harness.pages.calls).contains("show 5")
    }

    @Test
    fun `the pages on screen and two around them are watched`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 5, last = 7)
        harness.pages.ready(3)
        harness.pages.ready(9)
        harness.pages.ready(10)

        val state = reader.state.value
        assertThat(state.pages.keys).containsExactly(3, 4, 5, 6, 7, 8, 9)
        assertThat(state.pageState(3)).isInstanceOf(PageState.Ready::class.java)
        assertThat(state.pageState(9)).isInstanceOf(PageState.Ready::class.java)
        assertThat(state.pageState(10)).isEqualTo(PageState.Loading)
    }

    @Test
    fun `scrolling writes nothing, the strip at rest writes the page at the top`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 5, last = 7)
        reader.stripShows(first = 6, last = 8)
        assertThat(harness.store.positions).isEmpty()

        reader.stripRested()
        val kept = checkNotNull(harness.store.positions[AlbumFile])
        assertThat(kept.page).isEqualTo(7)
        assertThat(kept.percent).isWithin(1e-9).of(7 * 100.0 / Pages)

        val written = harness.outbox.log.toList()
        reader.stripRested()
        assertThat(harness.outbox.log).isEqualTo(written)
    }

    @Test
    fun `the pages around are fetched once the strip stayed at rest`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        advanceTimeBy(400.milliseconds)

        reader.stripShows(first = 6, last = 8)
        advanceTimeBy(400.milliseconds)
        assertThat(harness.pages.calls).doesNotContain("settle 6")

        reader.stripRested()
        advanceTimeBy(301.milliseconds)
        assertThat(harness.pages.calls).contains("settle 6")
    }

    @Test
    fun `the slider lands the strip on a page`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        assertThat(reader.state.value.landing).isEqualTo(Landing())

        reader.goTo(30)

        assertThat(reader.state.value.spread).isEqualTo(Spread(30))
        assertThat(reader.state.value.landing).isEqualTo(Landing(page = 30, serial = 1))
        assertThat(harness.pages.calls).contains("jump 30")

        reader.goTo(12)
        assertThat(reader.state.value.landing).isEqualTo(Landing(page = 12, serial = 2))
    }

    @Test
    fun `past the bottom of the last page is the end of the album`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        reader.stripShows(first = Pages - 1, last = Pages - 1)
        assertThat(reader.state.value.endPanel).isNull()

        reader.stripPastEnd()

        assertThat(reader.state.value.endPanel).isNotNull()
        val kept = checkNotNull(harness.store.positions[AlbumFile])
        assertThat(kept.page).isEqualTo(Pages)
        assertThat(kept.percent).isEqualTo(100.0)
    }

    @Test
    fun `leaving while the strip moves writes the page at the top`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 9, last = 11)
        reader.leave()

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(10)
        assertThat(harness.outbox.positions.single().page).isEqualTo(10)
    }

    @Test
    fun `the reader sent behind while the strip moves writes the page at the top`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 9, last = 11)
        reader.onBackground()

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(10)
    }

    @Test
    fun `panel by panel is not offered while reading vertically`() = runTest {
        val kept = InMemoryComicSettingsStore(directions = vertical, panels = setOf(Series), noticeSeen = true)
        val harness = ComicHarness(this, settings = kept)
        val reader = harness.reader()
        assertThat(harness.panels.cuts).isTrue()
        assertThat(reader.state.value.settings.panels).isTrue()

        assertThat(reader.state.value.panels.offered).isFalse()
        assertThat(reader.state.value.panels.on).isFalse()

        reader.setDirection(Direction.LeftToRight)
        assertThat(reader.state.value.panels.offered).isTrue()
        assertThat(reader.state.value.panels.on).isTrue()
    }

    @Test
    fun `back to pages turned, the reader is on the page that was at the top`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 20, last = 22)
        reader.setDirection(Direction.LeftToRight)

        assertThat(reader.state.value.vertical).isFalse()
        assertThat(reader.state.value.spread?.first).isEqualTo(20)
    }

    @Test
    fun `paged, what a strip tells is ignored`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val before = harness.pages.calls.toList()

        reader.stripShows(first = 5, last = 7)
        reader.stripRested()
        reader.stripPastEnd()

        assertThat(reader.state.value.spread).isEqualTo(Spread(0))
        assertThat(reader.state.value.endPanel).isNull()
        assertThat(harness.pages.calls).isEqualTo(before)
        assertThat(harness.store.positions).isEmpty()
    }

    @Test
    fun `every page on screen is asked for at once, each once while it stays there`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        harness.pages.calls.clear()

        reader.stripShows(first = 3, last = 5)
        assertThat(harness.pages.calls.take(3)).containsExactly("show 3", "show 4", "show 5").inOrder()

        reader.stripShows(first = 3, last = 5)
        reader.stripShows(first = 4, last = 6)
        assertThat(harness.pages.calls.filter { it in setOf("show 3", "show 4", "show 5", "show 6") })
            .containsExactly("show 3", "show 4", "show 5", "show 6").inOrder()
    }

    @Test
    fun `the two pages after the last one on screen are asked for after those on screen, none behind`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        harness.pages.calls.clear()

        reader.stripShows(first = 3, last = 5)
        assertThat(harness.pages.calls).containsExactly("show 3", "show 4", "show 5", "show 6", "show 7").inOrder()

        // A page further: the one that follows those asked ahead, and it alone.
        harness.pages.calls.clear()
        reader.stripShows(first = 4, last = 6)
        assertThat(harness.pages.calls).containsExactly("show 8")
    }

    @Test
    fun `no page is asked for past the last one of the album`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.goTo(Pages - 2)
        reader.stripShows(first = Pages - 2, last = Pages - 1)

        assertThat(harness.pages.calls).contains("show ${Pages - 1}")
        assertThat(harness.pages.calls).containsNoneOf("show $Pages", "show ${Pages + 1}")
    }

    @Test
    fun `a strip flung far drops what was fetched behind, and fetches the page at its top first`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        harness.pages.calls.clear()

        // Within the pages fetched ahead of the one the album opened on: each is asked for as it comes.
        reader.stripShows(first = 5, last = 6)
        assertThat(harness.pages.calls).doesNotContain("jump 5")

        // Further than those: what waits and what is fetched behind is dropped, before anything else is asked.
        harness.pages.calls.clear()
        reader.stripShows(first = 6, last = 7)
        assertThat(harness.pages.calls).containsExactly("jump 6", "show 7", "show 8", "show 9").inOrder()

        // On from there, near: no more is dropped, until the strip is as far again.
        harness.pages.calls.clear()
        reader.stripShows(first = 11, last = 12)
        assertThat(harness.pages.calls).containsExactly("show 11", "show 12", "show 13", "show 14").inOrder()
        harness.pages.calls.clear()
        reader.stripShows(first = 12, last = 13)
        assertThat(harness.pages.calls).containsExactly("jump 12", "show 13", "show 14", "show 15").inOrder()
    }

    @Test
    fun `a strip that came to rest is far from there only further on`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 4, last = 5)
        reader.stripRested()
        advanceTimeBy(301.milliseconds)
        assertThat(harness.pages.calls).contains("settle 4")

        // Five pages from where the pages around were fetched: still among them.
        reader.stripShows(first = 9, last = 10)
        assertThat(harness.pages.calls.filter { it.startsWith("jump") }).isEmpty()
        reader.stripShows(first = 10, last = 11)
        assertThat(harness.pages.calls).contains("jump 10")
    }

    @Test
    fun `the slider released on the page read lands the strip on the top of that page`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        reader.stripShows(first = 5, last = 6)
        harness.pages.calls.clear()

        reader.goTo(5)

        assertThat(reader.state.value.landing).isEqualTo(Landing(page = 5, serial = 1))
        assertThat(harness.pages.calls).contains("jump 5")
    }

    @Test
    fun `paged, the slider released on the spread shown goes nowhere`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.pages.calls.clear()

        reader.goTo(0)

        assertThat(reader.state.value.landing).isEqualTo(Landing())
        assertThat(harness.pages.calls).isEmpty()
    }

    @Test
    fun `past the end, the short pages above the last one stay on screen`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        reader.stripShows(first = Pages - 5, last = Pages - 1)

        reader.stripPastEnd()

        val state = reader.state.value
        assertThat(state.spread).isEqualTo(Spread(Pages - 1))
        assertThat(state.pages.keys).containsAtLeast(Pages - 5, Pages - 4, Pages - 3, Pages - 2, Pages - 1)
        assertThat(state.endPanel).isNotNull()
        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(Pages)
    }

    @Test
    fun `back to pages turned while the strip moves, the page at the top is written`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()

        reader.stripShows(first = 20, last = 22)
        reader.setDirection(Direction.LeftToRight)

        assertThat(reader.state.value.vertical).isFalse()
        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(21)
    }

    @Test
    fun `the reader cleared while the strip moves writes the page at the top`() = runTest {
        val harness = verticalHarness()
        val reader = harness.reader()
        val owner = ViewModelStore().apply { put("reader", reader) }

        reader.stripShows(first = 9, last = 11)
        owner.clear()

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(10)
        assertThat(harness.outbox.positions.single().page).isEqualTo(10)
    }

    @Test
    fun `a newer page of the server followed lands the strip there and writes nothing`() = runTest {
        val harness = verticalHarness()
        harness.serverHas(page = 12)
        val reader = harness.reader()
        reader.onForeground()
        harness.serverHas(page = 20, secondsFromStart = 60)
        reader.onForeground()
        assertThat(reader.state.value.newerPage).isEqualTo(20)

        reader.acceptNewer()

        assertThat(reader.state.value.spread).isEqualTo(Spread(19))
        assertThat(reader.state.value.landing).isEqualTo(Landing(page = 19, serial = 1))
        advanceTimeBy(5.seconds)
        assertThat(harness.repository.savedPositions).isEmpty()
        assertThat(harness.outbox.positions).isEmpty()
    }

    @Test
    fun `from pages turned to vertical, the reader stays on its page`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        repeat(3) { reader.next() }

        reader.setDirection(Direction.Vertical)

        assertThat(reader.state.value.vertical).isTrue()
        assertThat(reader.state.value.spread?.first).isEqualTo(3)
    }

    @Test
    fun `a wide page that arrives while reading vertically leaves each page a spread of its own`() = runTest {
        val two = InMemoryComicSettingsStore(ComicSettings(twoPages = TwoPages.Always), directions = vertical)
        val harness = ComicHarness(this, settings = two)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)

        harness.pages.ready(1, width = 1600, height = 1200)

        val state = reader.state.value
        assertThat(state.pageState(1)).isInstanceOf(PageState.Ready::class.java)
        assertThat(state.twoPages).isFalse()
        assertThat(state.spreads).hasSize(Pages)
        assertThat(state.spread).isEqualTo(Spread(0))
    }
}
