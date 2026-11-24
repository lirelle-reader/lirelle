package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val Series = DirectionScope.Series(SeriesId("4"))

/** What reading panel by panel is, told once on the device, the first time the mode is turned on. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PanelNoticeTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `the first time on the device, turning the mode on shows the notice and nothing is turned on yet`() = runTest {
        val kept = InMemoryComicSettingsStore(noticeSeen = false)
        val harness = ComicHarness(this, settings = kept)
        val reader = harness.reader()
        assertThat(reader.state.value.panels.notice).isFalse()

        reader.setPanelMode(true)

        assertThat(reader.state.value.panels.notice).isTrue()
        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(kept.panels).isEmpty()
        assertThat(kept.panelNoticeSeen.first()).isFalse()
        assertThat(harness.panels.calls.filter { it.startsWith("cutting") }).isEmpty()
    }

    @Test
    fun `closing the notice turns the mode on for the series, and the device is not told again`() = runTest {
        val kept = InMemoryComicSettingsStore(noticeSeen = false)
        val reader = ComicHarness(this, settings = kept).reader()
        reader.setPanelMode(true)

        reader.closePanelNotice()

        assertThat(reader.state.value.panels.notice).isFalse()
        assertThat(reader.state.value.panels.on).isTrue()
        assertThat(kept.panels).containsExactly(Series)
        assertThat(kept.panelNoticeSeen.first()).isTrue()

        reader.setPanelMode(false)
        reader.setPanelMode(true)

        assertThat(reader.state.value.panels.notice).isFalse()
        assertThat(reader.state.value.panels.on).isTrue()
    }

    @Test
    fun `a device already told turns the mode on at once, in another album too`() = runTest {
        val kept = InMemoryComicSettingsStore(noticeSeen = true)
        val reader = ComicHarness(this, settings = kept).reader()

        reader.setPanelMode(true)

        assertThat(reader.state.value.panels.notice).isFalse()
        assertThat(reader.state.value.panels.on).isTrue()
    }

    @Test
    fun `turning the mode off never shows the notice`() = runTest {
        val kept = InMemoryComicSettingsStore(panels = setOf(Series), noticeSeen = false)
        val reader = ComicHarness(this, settings = kept).reader()
        assertThat(reader.state.value.panels.on).isTrue()

        reader.setPanelMode(false)

        assertThat(reader.state.value.panels.notice).isFalse()
        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(kept.panelNoticeSeen.first()).isFalse()
    }

    @Test
    fun `a notice that is not shown is not closed, and turns nothing on`() = runTest {
        val kept = InMemoryComicSettingsStore(noticeSeen = false)
        val reader = ComicHarness(this, settings = kept).reader()

        reader.closePanelNotice()

        assertThat(reader.state.value.panels.on).isFalse()
        assertThat(kept.panelNoticeSeen.first()).isFalse()
    }

    @Test
    fun `the bars stay shown while the notice is read`() = runTest {
        val harness = ComicHarness(this, settings = InMemoryComicSettingsStore(noticeSeen = false))
        val reader = harness.reader()

        reader.setPanelMode(true)
        advanceTimeBy(harness.timings.bars + 10.seconds)

        assertThat(reader.state.value.barsShown).isTrue()
    }
}
