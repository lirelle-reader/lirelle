package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The bars that show and hide, and the settings, kept where each belongs. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderBarsAndSettingsTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the bars show at opening, then hide by themselves`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        assertThat(reader.state.value.barsShown).isTrue()

        advanceTimeBy(harness.timings.bars - 1.milliseconds)
        assertThat(reader.state.value.barsShown).isTrue()

        advanceTimeBy(2.milliseconds)
        assertThat(reader.state.value.barsShown).isFalse()
    }

    @Test
    fun `the bars the user shows stay until hidden`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.toggleBars()
        assertThat(reader.state.value.barsShown).isFalse()

        reader.toggleBars()
        advanceTimeBy(harness.timings.bars + 10.seconds)
        assertThat(reader.state.value.barsShown).isTrue()
    }

    @Test
    fun `the bars do not hide under a finger on the slider`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.previewPage(10)
        advanceTimeBy(harness.timings.bars + 10.seconds)

        assertThat(reader.state.value.barsShown).isTrue()
    }

    @Test
    fun `turning a page hides the bars, forward and back`() = runTest {
        val reader = ComicHarness(this).reader()
        assertThat(reader.state.value.barsShown).isTrue()

        reader.next()
        assertThat(reader.state.value.barsShown).isFalse()

        reader.toggleBars()
        reader.previous()
        assertThat(reader.state.value.barsShown).isFalse()
    }

    @Test
    fun `turning past the last page hides the bars too`() = runTest {
        val reader = ComicHarness(this).reader()
        reader.goTo(Pages - 1)
        assertThat(reader.state.value.barsShown).isTrue()

        reader.next()

        assertThat(reader.state.value.endPanel).isNotNull()
        assertThat(reader.state.value.barsShown).isFalse()
    }

    @Test
    fun `a move inside the page hides the bars, and they stay hidden`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.hideBars()
        assertThat(reader.state.value.barsShown).isFalse()

        reader.hideBars()
        advanceTimeBy(harness.timings.bars + 10.seconds)
        assertThat(reader.state.value.barsShown).isFalse()
    }

    @Test
    fun `the slider leaves the bars shown`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.previewPage(20)
        reader.goTo(20)
        advanceTimeBy(harness.timings.bars + 10.seconds)

        assertThat(reader.state.value.spreadIndex).isEqualTo(20)
        assertThat(reader.state.value.barsShown).isTrue()
    }

    @Test
    fun `an album opens with the settings kept, the direction of its series among them`() = runTest {
        val kept = InMemoryComicSettingsStore(
            initial = ComicSettings(fit = Fit.Width, twoPages = TwoPages.Never, background = Background.Grey),
            directions = mapOf(
                DirectionScope.Series(SeriesId("4")) to Direction.RightToLeft,
                DirectionScope.Book(AlbumBook) to Direction.LeftToRight,
            ),
        )

        val state = ComicHarness(this, settings = kept).reader().state.value

        assertThat(state.settings)
            .isEqualTo(ComicSettings(Fit.Width, TwoPages.Never, Direction.RightToLeft, Background.Grey))
    }

    @Test
    fun `the fit, the two pages and the background are the device's`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.setFit(Fit.Width)
        reader.setTwoPages(TwoPages.Always)
        reader.setBackground(Background.White)

        val expected = ComicSettings(Fit.Width, TwoPages.Always, Direction.LeftToRight, Background.White)
        assertThat(reader.state.value.settings).isEqualTo(expected)
        assertThat(harness.settings.settings(DirectionScope.Book(BookId("99"))).first()).isEqualTo(expected)
    }

    @Test
    fun `whether the whole page comes first is the device's, and the reader follows it`() = runTest {
        val store = InMemoryComicSettingsStore()
        val reader = ComicHarness(this, settings = store).reader()
        assertThat(reader.state.value.settings.wholePageFirst).isTrue()

        reader.setWholePageFirst(false)

        assertThat(reader.state.value.settings.wholePageFirst).isFalse()
        assertThat(reader.state.value.panels.wholePageFirst).isFalse()
        assertThat(store.shared.first().wholePageFirst).isFalse()
        // The device's: an album of no series opens with it.
        assertThat(store.settings(DirectionScope.Book(BookId("99"))).first().wholePageFirst).isFalse()

        reader.setWholePageFirst(true)
        assertThat(store.shared.first().wholePageFirst).isTrue()
    }

    @Test
    fun `the direction is kept for the series of the album`() = runTest {
        val store = InMemoryComicSettingsStore()
        val reader = ComicHarness(this, settings = store).reader()

        reader.setDirection(Direction.RightToLeft)

        assertThat(reader.state.value.settings.direction).isEqualTo(Direction.RightToLeft)
        assertThat(store.directions).containsExactly(DirectionScope.Series(SeriesId("4")), Direction.RightToLeft)
    }

    @Test
    fun `the direction of an album without a series is kept for the album`() = runTest {
        val store = InMemoryComicSettingsStore()
        val reader = ComicHarness(this, detail = sampleComicDetail(series = null), settings = store).reader()
        assertThat(reader.state.value.series).isNull()
        assertThat(reader.state.value.directionScope).isEqualTo(DirectionScope.Book(AlbumBook))

        reader.setDirection(Direction.RightToLeft)

        assertThat(reader.state.value.settings.direction).isEqualTo(Direction.RightToLeft)
        assertThat(store.directions).containsExactly(DirectionScope.Book(AlbumBook), Direction.RightToLeft)
    }

    @Test
    fun `an album whose server reads its series right to left opens so, until the user chooses`() = runTest {
        val store = InMemoryComicSettingsStore()
        val manga = sampleComicDetail().copy(pageDirection = PageDirection.RightToLeft)
        val reader = ComicHarness(this, detail = manga, settings = store).reader()

        assertThat(reader.state.value.settings.direction).isEqualTo(Direction.RightToLeft)
        // The server's direction is no choice of the user's: nothing is kept for it.
        assertThat(store.directions).isEmpty()

        reader.setDirection(Direction.LeftToRight)

        assertThat(reader.state.value.settings.direction).isEqualTo(Direction.LeftToRight)
        assertThat(store.directions).containsExactly(DirectionScope.Series(SeriesId("4")), Direction.LeftToRight)
    }

    @Test
    fun `the direction the user chose for the series wins over the server's`() = runTest {
        val store = InMemoryComicSettingsStore(
            directions = mapOf(DirectionScope.Series(SeriesId("4")) to Direction.LeftToRight),
        )
        val manga = sampleComicDetail().copy(pageDirection = PageDirection.RightToLeft)

        val state = ComicHarness(this, detail = manga, settings = store).reader().state.value

        assertThat(state.settings.direction).isEqualTo(Direction.LeftToRight)
    }

    @Test
    fun `an album whose server reads its series vertically opens so`() = runTest {
        val store = InMemoryComicSettingsStore()
        val webtoon = sampleComicDetail().copy(pageDirection = PageDirection.Vertical)

        val state = ComicHarness(this, detail = webtoon, settings = store).reader().state.value

        assertThat(state.settings.direction).isEqualTo(Direction.Vertical)
        assertThat(store.directions).isEmpty()
    }

    @Test
    fun `the direction the user chose for the series wins over the server's vertical`() = runTest {
        val store = InMemoryComicSettingsStore(
            directions = mapOf(DirectionScope.Series(SeriesId("4")) to Direction.RightToLeft),
        )
        val webtoon = sampleComicDetail().copy(pageDirection = PageDirection.Vertical)

        val state = ComicHarness(this, detail = webtoon, settings = store).reader().state.value

        assertThat(state.settings.direction).isEqualTo(Direction.RightToLeft)
    }

    @Test
    fun `an album whose server says no direction, or left to right, opens left to right`() = runTest {
        val said = sampleComicDetail().copy(pageDirection = PageDirection.LeftToRight)

        assertThat(ComicHarness(this).reader().state.value.settings.direction).isEqualTo(Direction.LeftToRight)
        assertThat(ComicHarness(this, detail = said).reader().state.value.settings.direction)
            .isEqualTo(Direction.LeftToRight)
    }

    @Test
    fun `changing the direction moves the reader nowhere`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()

        reader.setDirection(Direction.RightToLeft)

        assertThat(reader.state.value.spreadIndex).isEqualTo(11)
    }
}
