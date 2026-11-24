package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A look elsewhere in the book, while "Go back" is offered, is not where the user is reading. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPeekTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    /** A reader that turned one page, to [SecondCfi], and whose position has been sent. */
    private fun TestScope.readingOn(harness: ReaderHarness): ReaderViewModel {
        val viewModel = harness.reading()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.9))
        advanceTimeBy(10.seconds)
        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
        return viewModel
    }

    private fun position(cfi: String, percent: Double) = ReadingPosition(FileId("1"), cfi, percent, updatedAt = null)

    @Test
    fun `a jump is shown but neither kept nor sent while go back is offered`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)

        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3, tocId = 1))
        advanceTimeBy(1.minutes)

        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(viewModel.state.value.location?.fraction).isEqualTo(0.3)
        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)
    }

    @Test
    fun `a look around with the slider and a page turn is still not sent`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)

        viewModel.seekTo(0.3)
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/4)", fraction = 0.31))
        advanceTimeBy(1.minutes)

        assertThat(viewModel.state.value.goBackShown).isTrue()
        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
    }

    @Test
    fun `a link followed inside the book is not sent while go back is offered`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        readingOn(harness)

        harness.engine.emit(PageMessage.Link(href = "two.xhtml#note", external = false))
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        advanceTimeBy(1.minutes)

        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
    }

    @Test
    fun `reading on from the place jumped to makes it the position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)
        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))

        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/4)", fraction = 0.31))
        harness.engine.emit(relocated(cfi = "epubcfi(/6/6!/4/6)", fraction = 0.32))

        assertThat(viewModel.state.value.goBackShown).isFalse()
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo("epubcfi(/6/6!/4/6)")
        advanceTimeBy(10.seconds)
        assertThat(harness.repository.savedPositions)
            .containsExactly(position(SecondCfi, 90.0), position("epubcfi(/6/6!/4/6)", 32.0)).inOrder()
    }

    @Test
    fun `going back sends nothing at all`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)
        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        advanceTimeBy(30.seconds)

        viewModel.goBack()
        assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.GoTo.Cfi(SecondCfi))
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.9))
        advanceTimeBy(1.minutes)

        assertThat(viewModel.state.value.goBackShown).isFalse()
        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(SecondCfi)

        // Reading goes on from there as usual.
        harness.engine.emit(relocated(cfi = "epubcfi(/6/4!/4/12)", fraction = 0.91))
        advanceTimeBy(10.seconds)
        assertThat(harness.repository.savedPositions.last()).isEqualTo(position("epubcfi(/6/4!/4/12)", 91.0))
    }

    @Test
    fun `going back through two jumps sends nothing either`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)
        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        viewModel.seekTo(0.5)
        harness.engine.emit(relocated(cfi = "epubcfi(/6/8!/4/2)", fraction = 0.5))

        viewModel.goBack()
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))
        advanceTimeBy(1.minutes)
        assertThat(viewModel.state.value.goBackShown).isTrue()
        viewModel.goBack()
        harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.9))
        advanceTimeBy(1.minutes)

        assertThat(harness.repository.savedPositions).containsExactly(position(SecondCfi, 90.0))
    }

    @Test
    fun `leaving while go back is offered sends the place the user left at`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        val viewModel = readingOn(harness)
        viewModel.goToToc(SampleToc[1])
        harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))

        viewModel.onLeave()

        assertThat(harness.repository.savedPositions)
            .containsExactly(position(SecondCfi, 90.0), position(ThirdCfi, 30.0)).inOrder()
        assertThat(harness.positions.positions[FileId("1")]?.cfi).isEqualTo(ThirdCfi)
    }

    @Test
    fun `going to the background while go back is offered sends the place, and going back the first one again`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = readingOn(harness)
            harness.clock.advance(1.minutes)
            viewModel.goToToc(SampleToc[1])
            harness.engine.emit(relocated(cfi = ThirdCfi, fraction = 0.3))

            viewModel.onPaused()

            assertThat(harness.repository.savedPositions.last()).isEqualTo(position(ThirdCfi, 30.0))
            assertThat(harness.repository.savedSessions.last().second.endPercent).isEqualTo(30.0)

            viewModel.onResumed()
            viewModel.goBack()
            harness.engine.emit(relocated(cfi = SecondCfi, fraction = 0.9))
            advanceTimeBy(10.seconds)

            assertThat(harness.repository.savedPositions)
                .containsExactly(position(SecondCfi, 90.0), position(ThirdCfi, 30.0), position(SecondCfi, 90.0))
                .inOrder()
        }
}
