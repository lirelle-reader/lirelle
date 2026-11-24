package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.ReaderHarness
import app.tfdn.lirelle.feature.reader.SampleToc
import app.tfdn.lirelle.feature.reader.SecondCfi
import app.tfdn.lirelle.feature.reader.StartCfi
import app.tfdn.lirelle.feature.reader.ThirdCfi
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageDirection
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SentenceCfi = "epubcfi(/6/2!/4/8)"

private val Elsewhere = ReadingPosition(TextFile,ThirdCfi, 80.0, Instant.parse("2026-11-14T11:00:00Z"))

/** The text position and the reading session around a narration of the book. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNarrationSessionTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private fun ReaderHarness.cfiAsks() = engine.sent.filterIsInstance<HostMessage.NarrationCfi>()

    /** The narration started from the page, at the sentence [id] of section 0. */
    private fun ReaderHarness.startAt(id: String) {
        val find = engine.sent.filterIsInstance<HostMessage.NarrationFind>().last()
        engine.emit(PageMessage.NarrationFound(find.requestId, 0, id))
    }

    // ---------------------------------------------------------------- the text position

    @Test
    fun `pages turned by following send no text position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(0, "a1")

        harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.3, byNarration = true))
        advanceTimeBy(10.seconds)

        assertThat(harness.positions.positions).isEmpty()
        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `the sentence heard the reader opens at is not saved over`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.player.hear(2, "c2", playing = false)
        val viewModel = harness.reading()

        harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.6, sectionIndex = 2, byNarration = true))
        advanceTimeBy(10.seconds)
        viewModel.onLeave()

        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `while this book's narration is loaded, the server's newer place is not offered on return`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi(playing = false)
            harness.repository.positionResult = ApiResult.Success(Elsewhere)

            viewModel.onResumed()

            assertThat(viewModel.state.value.newerPosition).isNull()
        }

    @Test
    fun `another book's narration held, the server's newer place is offered on return as usual`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi(bookId = BookId("99"))
            harness.player.failure.value = NarrationFailure(NarratedBook, TextFile)
            harness.repository.positionResult = ApiResult.Success(Elsewhere)

            viewModel.onResumed()

            assertThat(viewModel.state.value.newerPosition).isNotNull()
        }

    @Test
    fun `pages turned by the user while the narration plays send the position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(0, "a1")

        harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.25))
        advanceTimeBy(3.seconds)

        assertThat(harness.repository.savedPositions)
            .containsExactly(ReadingPosition(TextFile,SecondCfi, 25.0, updatedAt = null))
    }

    @Test
    fun `a start before the sentence heard sends the position of the sentence at once`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(2, "c1")
            viewModel.startHere()

            harness.startAt("a2")

            val ask = harness.cfiAsks().single()
            assertThat(ask.sectionIndex).isEqualTo(0)
            assertThat(ask.id).isEqualTo("a2")

            harness.engine.emit(PageMessage.NarrationCfiOf(ask.requestId, SentenceCfi))

            assertThat(harness.repository.savedPositions)
                .containsExactly(ReadingPosition(TextFile,SentenceCfi, 10.0, updatedAt = null))
        }

    @Test
    fun `a start after the sentence heard, or with none heard, asks no position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        viewModel.startHere()
        harness.startAt("a2")
        harness.player.hear(0, "a2")

        viewModel.startHere()
        harness.startAt("a3")

        assertThat(harness.cfiAsks()).isEmpty()
    }

    @Test
    fun `small moves back of the narration send no position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(0, "a3")

        harness.player.hear(0, "a2")
        harness.player.hear(0, "a1")
        advanceTimeBy(10.seconds)

        assertThat(harness.cfiAsks()).isEmpty()
        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `a section chosen in the panel before the sentence heard sends the position landed on`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi().copy(sectionIndex = 2, sectionNumber = 2)
            harness.player.hear(2, "c1")

            viewModel.narrationControls.goToSection(0)
            harness.player.hear(0, "a1")

            assertThat(harness.player.actions.calls).containsExactly("goToSection(0)")
            val ask = harness.cfiAsks().single()
            assertThat(ask.sectionIndex to ask.id).isEqualTo(0 to "a1")
            // The page says how far into the book the sentence is: the page shown is still where it was.
            harness.engine.emit(PageMessage.NarrationCfiOf(ask.requestId, SentenceCfi, fraction = 0.05))
            assertThat(harness.repository.savedPositions)
                .containsExactly(ReadingPosition(TextFile,SentenceCfi, 5.0, updatedAt = null))
        }

    @Test
    fun `a seek back within the sentence while paused, then the previous sentence, sends nothing`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi(playing = false).copy(elapsedMs = 9_000)
            harness.player.hear(0, "a3", playing = false)

            viewModel.narrationControls.seekInSection(0.7f)
            viewModel.narrationControls.previousSentence()
            harness.player.hear(0, "a2", playing = false)

            assertThat(harness.cfiAsks()).isEmpty()
        }

    @Test
    fun `go back to a place before the sentence heard sends the position landed on, after it sends none`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi().copy(goBackShown = true)
            harness.player.hear(2, "c1")
            val controls = viewModel.narrationControls

            controls.goBack()
            harness.player.hear(0, "a1")
            controls.goBack()
            harness.player.hear(2, "c2")

            assertThat(harness.player.actions.calls).containsExactly("goBack", "goBack").inOrder()
            assertThat(harness.cfiAsks().map { it.id }).containsExactly("a1")
        }

    @Test
    fun `a seek back in the section sends the position landed on`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.narration.value = narrationUi().copy(elapsedMs = 9_000)
        harness.player.hear(0, "a3")

        viewModel.narrationControls.seekInSection(0.1f)
        harness.player.hear(0, "a1")

        assertThat(harness.cfiAsks().single().id).isEqualTo("a1")
    }

    @Test
    fun `a section after the sentence heard, a seek forward, the previous sentence or a skip back send none`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.narration.value = narrationUi().copy(elapsedMs = 4_000)
            harness.player.hear(0, "a2")
            val controls = viewModel.narrationControls

            controls.goToSection(1)
            harness.player.hear(2, "c1")
            controls.seekInSection(0.9f)
            harness.player.hear(2, "c2")
            controls.previousSentence()
            harness.player.hear(2, "c1")
            controls.back()
            harness.player.hear(0, "a3")

            assertThat(harness.cfiAsks()).isEmpty()
        }

    @Test
    fun `a position the page could not find is not sent`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(2, "c1")
        viewModel.startHere()
        harness.startAt("a1")

        harness.engine.emit(PageMessage.NarrationCfiOf(harness.cfiAsks().single().requestId, null))
        advanceTimeBy(10.seconds)

        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `a position asked before the user moved away is not sent`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.player.hear(2, "c1")
        viewModel.startHere()
        harness.startAt("a2")

        harness.engine.emit(PageMessage.Turning)
        harness.engine.emit(PageMessage.NarrationCfiOf(harness.cfiAsks().single().requestId, SentenceCfi))
        advanceTimeBy(10.seconds)

        assertThat(harness.repository.savedPositions).isEmpty()
    }

    // ---------------------------------------------------------------- the reading session

    @Test
    fun `no reading time counts while the narration plays, and reading on goes on with the session`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.clock.advance(30.seconds)
            harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.2))

            harness.player.hear(0, "a1")
            harness.clock.advance(2.minutes)
            harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.3, byNarration = true))
            harness.player.hear(0, "a2", playing = false)
            harness.clock.advance(1.minutes)
            harness.engine.emit(pageAt(cfi = StartCfi, fraction = 0.35))
            harness.clock.advance(20.seconds)
            harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.4))
            viewModel.onLeave()

            val sessions = harness.repository.savedSessions.map { it.second }
            assertThat(sessions.first().activeSeconds).isEqualTo(30)
            assertThat(sessions.last().sessionId).isEqualTo(sessions.first().sessionId)
            assertThat(sessions.last().activeSeconds).isEqualTo(50)
        }

    @Test
    fun `after more than five minutes of narration, reading on starts another session`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.clock.advance(30.seconds)
            harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.2))
            harness.player.hear(0, "a1")
            harness.clock.advance(6.minutes)
            harness.player.heard.value = null

            harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.3))
            harness.clock.advance(20.seconds)
            harness.engine.emit(pageAt(cfi = StartCfi, fraction = 0.35))
            viewModel.onLeave()

            val sessions = harness.repository.savedSessions.map { it.second }
            assertThat(sessions.first().activeSeconds).isEqualTo(30)
            assertThat(sessions.last().sessionId).isNotEqualTo(sessions.first().sessionId)
            assertThat(sessions.last().activeSeconds).isEqualTo(20)
        }

    @Test
    fun `opened during a paused narration, at the sentence heard, the reading session starts there`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            harness.player.hear(2, "c1", playing = false)
            val viewModel = harness.viewModel()
            viewModel.onEngine(harness.engine)
            harness.engine.emit(PageMessage.Ready)
            harness.engine.emit(PageMessage.Opened(SampleToc, PageDirection.Ltr, fixedLayout = false, title = null))
            harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.6, sectionIndex = 2, byNarration = true))
            harness.clock.advance(30.seconds)

            harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.65))
            viewModel.onLeave()

            val session = harness.repository.savedSessions.single().second
            assertThat(session.activeSeconds).isEqualTo(30)
            assertThat(session.endPercent).isEqualTo(65.0)
        }

    @Test
    fun `opened during another book's narration, reading time counts as usual`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.player.hear(0, "x1", bookId = BookId("99"))
        val viewModel = harness.reading()
        harness.clock.advance(30.seconds)

        harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.2))
        viewModel.onLeave()

        assertThat(harness.repository.savedSessions.single().second.activeSeconds).isEqualTo(30)
    }

    @Test
    fun `opened while the narration plays, pages turned count no reading time`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.player.hear(0, "a1")
        val viewModel = harness.reading()
        harness.clock.advance(1.minutes)

        harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.2))
        harness.clock.advance(1.minutes)
        harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.3))
        viewModel.onLeave()

        assertThat(harness.repository.savedSessions).isEmpty()
    }

    @Test
    fun `back in front while the narration plays counts no reading time`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = harness.reading()
        harness.clock.advance(30.seconds)
        harness.engine.emit(pageAt(cfi = SecondCfi, fraction = 0.2))
        harness.player.hear(0, "a1")
        viewModel.onPaused()
        harness.clock.advance(1.minutes)

        viewModel.onResumed()
        harness.clock.advance(1.minutes)
        harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.3, byNarration = true))
        viewModel.onLeave()

        assertThat(harness.repository.savedSessions.map { it.second.activeSeconds }.distinct()).containsExactly(30)
    }
}
