package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.BrightnessPhase
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The book on screen: where the reader is, taps, jumps and "Go back", back, settings, links, keys. */
class ReaderPageTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private fun tap(x: Double) = PageMessage.Tap(x = x, y = 0.5)

    @Test
    fun `a relocation says where the reader is`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.426, tocId = 1))

        val location = viewModel.state.value.location
        assertThat(location).isEqualTo(Location("Two", tocId = 1, fraction = 0.426, page = 2, pages = 9, atEnd = false))
        assertThat(location?.percent).isEqualTo(42)
        assertThat(location?.pagesLeftInChapter).isEqualTo(7)
    }

    @Test
    fun `the percentage only reaches 100 at the very end`() {
        fun percent(fraction: Double) = Location(null, null, fraction, null, null, atEnd = false).percent

        assertThat(percent(0.0)).isEqualTo(0)
        assertThat(percent(0.999)).isEqualTo(99)
        assertThat(percent(1.0)).isEqualTo(100)
        assertThat(Location(null, null, 0.5, null, null, atEnd = false).pagesLeftInChapter).isNull()
    }

    @Test
    fun `the percentage of the chapter is the share of its pages reached, rounded down`() {
        fun percent(page: Int?, pages: Int?) = Location(null, null, 0.5, page, pages, atEnd = false).chapterPercent

        assertThat(percent(1, 9)).isEqualTo(11)
        assertThat(percent(5, 9)).isEqualTo(55)
        assertThat(percent(8, 9)).isEqualTo(88)
        assertThat(percent(9, 9)).isEqualTo(100)
        assertThat(percent(1, 1)).isEqualTo(100)
        assertThat(percent(1, 300)).isEqualTo(0)
    }

    @Test
    fun `the percentage of the chapter is unknown when its pages are not counted`() {
        fun percent(page: Int?, pages: Int?) = Location(null, null, 0.5, page, pages, atEnd = false).chapterPercent

        assertThat(percent(null, null)).isNull()
        assertThat(percent(3, null)).isNull()
        assertThat(percent(null, 9)).isNull()
        assertThat(percent(1, 0)).isNull()
        assertThat(percent(0, 0)).isNull()
        // Never out of bounds, whatever the page says.
        assertThat(percent(12, 9)).isEqualTo(100)
        assertThat(percent(0, 9)).isEqualTo(0)
    }

    @Test
    fun `a tap in the centre shows the controls, another hides them`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(tap(0.5))
        assertThat(viewModel.state.value.controlsShown).isTrue()

        harness.engine.emit(tap(0.21))
        assertThat(viewModel.state.value.controlsShown).isFalse()

        harness.engine.emit(tap(0.79))
        assertThat(viewModel.state.value.controlsShown).isTrue()
        assertThat(harness.engine.sent).hasSize(1)
    }

    @Test
    fun `taps in the side fifths turn the page and hide the controls`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()

        harness.engine.emit(tap(0.19))

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Prev)
        assertThat(viewModel.state.value.controlsShown).isFalse()

        viewModel.toggleControls()
        harness.engine.emit(tap(0.81))

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `in a right-to-left book the sides are the other way round`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading(PageDirection.Rtl)

        harness.engine.emit(tap(0.05))
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)

        harness.engine.emit(tap(0.95))
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Prev)
    }

    @Test
    fun `a page turned by a swipe hides the controls, a relocation the reader asked for does not`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.toggleControls()

            viewModel.seekTo(0.5)
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))
            assertThat(viewModel.state.value.controlsShown).isTrue()

            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.52))
            assertThat(viewModel.state.value.controlsShown).isFalse()
        }

    @Test
    fun `chapters are stepped through along the table of contents`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.previousChapter()
        assertThat(harness.engine.sent).hasSize(1)

        viewModel.nextChapter()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Href("two.xhtml"))

        harness.engine.emit(relocated(cfi = ThirdCfi, tocId = 2))
        viewModel.nextChapter()
        assertThat(harness.engine.sent).hasSize(2)

        viewModel.previousChapter()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Href("two.xhtml"))
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `a jump from the contents offers to go back, and going back returns there`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.showSheet(ReaderSheet.Contents)

        viewModel.goToToc(SampleToc[2])

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Href("three.xhtml"))
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.openSheet).isNull()
        assertThat(viewModel.state.value.controlsShown).isFalse()

        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.6, tocId = 2))
        viewModel.goBack()

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
        assertThat(viewModel.state.value.goBackShown).isFalse()
    }

    @Test
    fun `the slider is a jump too`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.seekTo(0.75)

        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Fraction(0.75))
        assertThat(viewModel.state.value.goBackShown).isTrue()

        viewModel.seekTo(7.0)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Fraction(1.0))
    }

    @Test
    fun `a link of the book, which the page followed, offers to go back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Link(href = "notes.xhtml#n1", external = false))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.9))

        assertThat(harness.engine.sent).hasSize(1)
        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.externalLink).isNull()

        viewModel.goBack()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
    }

    @Test
    fun `two pages turned after a jump withdraw the offer`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.seekTo(0.5)
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))

        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/8)", fraction = 0.51))
        assertThat(viewModel.state.value.goBackShown).isTrue()

        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/14)", fraction = 0.52))
        assertThat(viewModel.state.value.goBackShown).isFalse()

        viewModel.goBack()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Fraction(0.5))
    }

    @Test
    fun `places the user did not move to, as after a resize, neither hide the controls nor withdraw the offer`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.seekTo(0.5)
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.5))
            viewModel.toggleControls()

            harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/8)", fraction = 0.51).copy(userMove = false))
            harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/14)", fraction = 0.52).copy(userMove = false))

            assertThat(viewModel.state.value.goBackShown).isTrue()
            assertThat(viewModel.state.value.controlsShown).isTrue()
        }

    @Test
    fun `back closes the sheet, then goes back from the jump, then hides the controls, then leaves`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.seekTo(0.5)
            viewModel.toggleControls()
            viewModel.showSheet(ReaderSheet.Contents)

            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.openSheet).isNull()
            assertThat(viewModel.state.value.goBackShown).isTrue()

            assertThat(viewModel.onBack()).isTrue()
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(StartCfi))
            assertThat(viewModel.state.value.goBackShown).isFalse()
            assertThat(viewModel.state.value.controlsShown).isTrue()

            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.controlsShown).isFalse()

            assertThat(viewModel.onBack()).isFalse()
        }

    @Test
    fun `back from a sheet opened from the settings returns to the settings`() = runTest(mainRule.dispatcher) {
        val viewModel = harness().reading()

        listOf(ReaderSheet.RunningLines, ReaderSheet.MoreOptions).forEach { sheet ->
            viewModel.showSheet(sheet)
            assertThat(viewModel.onBack()).isTrue()
            assertThat(viewModel.state.value.openSheet).isEqualTo(ReaderSheet.Settings)
        }
        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.state.value.openSheet).isNull()

        viewModel.showSheet(ReaderSheet.MoreOptions)
        viewModel.dismissSheet()
        assertThat(viewModel.state.value.openSheet).isNull()
    }

    @Test
    fun `a change of settings is stored and sent to the page at once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.updateSettings {
            it.copy(fontSizeStep = 9, theme = ReaderTheme.Black, brightnessEdge = BrightnessEdge.Off)
        }

        assertThat(harness.settings.current.fontSizeStep).isEqualTo(9)
        assertThat(viewModel.state.value.settings).isEqualTo(harness.settings.current)
        assertThat(viewModel.state.value.colours).isEqualTo(ReaderTheme.Black.colours(systemDark = false))
        val black = ReaderTheme.Black.colours(systemDark = false)
        val expected = harness.settings.current.toPageSettings(black, false, EnglishNoteLabels)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ApplySettings(expected))
    }

    @Test
    fun `a setting the page does not draw is stored without a word to the page`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.updateSettings { it.copy(volumeKeys = true) }

        assertThat(harness.settings.current.volumeKeys).isTrue()
        assertThat(harness.engine.sent).hasSize(1)
    }

    @Test
    fun `a change of layout is not taken for a page turned`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()

        viewModel.updateSettings { it.copy(fontSizeStep = 9) }
        harness.engine.emit(relocated(cfi = SecondCfi))

        assertThat(viewModel.state.value.controlsShown).isTrue()
    }

    @Test
    fun `new note labels do not make the next swipe a jump`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()

        viewModel.onNoteLabels(PageNoteLabels(goTo = "Aller \u00e0 la note", title = "Note"))
        harness.engine.emit(relocated(cfi = SecondCfi))

        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `the same labels again send nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        val labels = PageNoteLabels(goTo = "Aller \u00e0 la note", title = "Note")

        viewModel.onNoteLabels(labels)
        viewModel.onNoteLabels(labels)

        assertThat(harness.engine.sent).hasSize(2)
    }

    @Test
    fun `a change of colours alone does not make the next swipe a jump`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.toggleControls()

        viewModel.updateSettings { it.copy(theme = ReaderTheme.Black) }
        harness.engine.emit(relocated(cfi = SecondCfi))

        assertThat(viewModel.state.value.controlsShown).isFalse()
    }

    @Test
    fun `labels given before the book opens are in the open message`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.viewModel()
        val french = PageNoteLabels(goTo = "Aller \u00e0 la note", title = "Note")

        viewModel.onNoteLabels(french)
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        val open = harness.engine.sent.filterIsInstance<HostMessage.Open>().single()
        assertThat(open.settings.noteLabels).isEqualTo(french)
    }

    @Test
    fun `the system theme follows the device`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.onSystemDark(true)

        val dark = ReaderTheme.Dark.colours(systemDark = false)
        assertThat(viewModel.state.value.colours).isEqualTo(dark)
        val expected = harness.settings.current.toPageSettings(dark, false, EnglishNoteLabels)
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.ApplySettings(expected))
    }

    @Test
    fun `two pages are shown side by side in a wide window only`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        viewModel.onWindow(wide = true)
        assertThat((harness.engine.sent.last() as HostMessage.ApplySettings).settings.maxColumns).isEqualTo(2)

        viewModel.onWindow(wide = true)
        assertThat(harness.engine.sent).hasSize(2)

        viewModel.onWindow(wide = false)
        assertThat((harness.engine.sent.last() as HostMessage.ApplySettings).settings.maxColumns).isEqualTo(1)
    }

    @Test
    fun `the labels of the note panel reach the page in the app's language`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        val french = PageNoteLabels(goTo = "Aller \u00e0 la note", title = "Note")

        viewModel.onNoteLabels(french)

        assertThat((harness.engine.sent.last() as HostMessage.ApplySettings).settings.noteLabels).isEqualTo(french)
    }

    @Test
    fun `a brightness drag starts from the window's brightness and stays within bounds`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            val brightness = { viewModel.state.value.settings.brightness }
            viewModel.onSystemBrightness(0.4f)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Start, 0.0))
            assertThat(viewModel.state.value.adjustingBrightness).isTrue()
            assertThat(brightness()).isNull()

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, 0.25))
            assertThat(brightness()).isWithin(0.0001f).of(0.65f)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, 0.9))
            assertThat(brightness()).isEqualTo(1f)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, -1.0))
            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, -1.0))
            assertThat(brightness()).isEqualTo(0.01f)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.End, 0.0))
            assertThat(viewModel.state.value.adjustingBrightness).isFalse()
            assertThat(harness.settings.current.brightness).isEqualTo(0.01f)
            // Brightness is the window's business: the page is told nothing.
            assertThat(harness.engine.sent).hasSize(1)
        }

    @Test
    fun `a brightness drag is shown at each move and stored once, when the finger is lifted`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.onSystemBrightness(0.4f)
            val before = harness.settings.writes
            // A disk slower than the finger.
            val disk = CompletableDeferred<Unit>()
            harness.settings.gate = disk

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Start, 0.0))
            repeat(30) { move ->
                harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, 0.01))
                assertThat(viewModel.state.value.settings.brightness).isWithin(0.0001f).of(0.41f + move * 0.01f)
            }
            assertThat(harness.settings.writes).isEqualTo(before)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.End, 0.01))
            assertThat(harness.settings.writes).isEqualTo(before + 1)
            assertThat(viewModel.state.value.settings.brightness).isWithin(0.0001f).of(0.71f)

            disk.complete(Unit)
            assertThat(harness.settings.current.brightness).isWithin(0.0001f).of(0.71f)
            assertThat(viewModel.state.value.settings.brightness).isWithin(0.0001f).of(0.71f)
            assertThat(harness.settings.writes).isEqualTo(before + 1)
        }

    @Test
    fun `a drag that changed nothing writes nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        val before = harness.settings.writes

        harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Start, 0.0))
        harness.engine.emit(PageMessage.Brightness(BrightnessPhase.End, 0.0))

        assertThat(harness.settings.writes).isEqualTo(before)
        assertThat(harness.settings.current.brightness).isNull()
    }

    @Test
    fun `a brightness still under the finger is stored when the reader is left`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.onSystemBrightness(0.4f)
        harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Start, 0.0))
        harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, 0.2))
        assertThat(harness.settings.current.brightness).isNull()

        viewModel.onLeave()

        assertThat(harness.settings.current.brightness).isWithin(0.0001f).of(0.6f)
    }

    @Test
    fun `another setting changed during a drag keeps the brightness under the finger`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            viewModel.onSystemBrightness(0.4f)
            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Start, 0.0))
            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.Move, 0.2))

            // Something else writes the settings: what the store then says has the old brightness.
            harness.settings.update { it.copy(hyphenate = false) }

            assertThat(viewModel.state.value.settings.hyphenate).isFalse()
            assertThat(viewModel.state.value.settings.brightness).isWithin(0.0001f).of(0.6f)

            harness.engine.emit(PageMessage.Brightness(BrightnessPhase.End, 0.0))
            assertThat(harness.settings.current).isEqualTo(viewModel.state.value.settings)
        }

    @Test
    fun `a web link waits for the user's word, then is handed over once`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Link(href = "https://example.org/page", external = true))

        assertThat(viewModel.state.value.externalLink).isEqualTo("https://example.org/page")
        assertThat(viewModel.state.value.goBackShown).isFalse()

        viewModel.confirmExternalLink()
        viewModel.confirmExternalLink()

        assertThat(viewModel.state.value.externalLink).isNull()
        assertThat(viewModel.events.first()).isEqualTo(ReaderEvent.OpenUrl("https://example.org/page"))
        viewModel.leave()
        assertThat(viewModel.events.first()).isEqualTo(ReaderEvent.Leave)
    }

    @Test
    fun `a web link can be declined, by its button or with back`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        harness.engine.emit(PageMessage.Link(href = "https://example.org/page", external = true))
        viewModel.dismissExternalLink()
        assertThat(viewModel.state.value.externalLink).isNull()

        harness.engine.emit(PageMessage.Link(href = "https://example.org/page", external = true))
        assertThat(viewModel.onBack()).isTrue()
        assertThat(viewModel.state.value.externalLink).isNull()
    }

    @Test
    fun `an outside link that is not a web address is ignored`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///etc/hosts", "content://x/y", "mailto:a@b.c")
            .forEach { harness.engine.emit(PageMessage.Link(href = it, external = true)) }

        assertThat(viewModel.state.value.externalLink).isNull()
        viewModel.confirmExternalLink()
        viewModel.leave()
        assertThat(viewModel.events.first()).isEqualTo(ReaderEvent.Leave)
    }

    @Test
    fun `volume keys turn pages when the option is on`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isFalse()
        assertThat(harness.engine.sent).hasSize(1)

        viewModel.updateSettings { it.copy(volumeKeys = true) }
        viewModel.toggleControls()

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isTrue()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Next)
        assertThat(viewModel.state.value.controlsShown).isFalse()
        assertThat(viewModel.onVolumeKey(volumeDown = false)).isTrue()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.Prev)
    }

    @Test
    fun `volume keys are left alone under a sheet and before the book is shown`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.settings.update { it.copy(volumeKeys = true) }
        val viewModel = harness.viewModel()
        viewModel.onEngine(harness.engine)
        harness.engine.emit(PageMessage.Ready)

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isFalse()

        harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
        viewModel.showSheet(ReaderSheet.Settings)

        assertThat(viewModel.onVolumeKey(volumeDown = true)).isFalse()
        assertThat(harness.engine.sent).hasSize(1)
    }
}
