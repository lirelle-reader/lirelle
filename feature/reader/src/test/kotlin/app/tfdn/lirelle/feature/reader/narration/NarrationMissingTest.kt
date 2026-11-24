package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.feature.reader.ReaderHarness
import app.tfdn.lirelle.feature.reader.ReaderViewModel
import app.tfdn.lirelle.feature.reader.ThirdCfi
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A sentence the narration reads that is not in its section's document: logged, and followed to its section. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NarrationMissingTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness() = ReaderHarness(this, folder.root)

    private val ReaderViewModel.narration get() = state.value.narration

    @Test
    fun `a sentence missing from its section takes a following page to the section's start`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(2, "c1")

            harness.engine.emit(PageMessage.NarrationMissing(2, "c1"))
            harness.engine.emit(pageAt(cfi = ThirdCfi, sectionIndex = 2).copy(byHost = true))

            assertThat(harness.engine.sent).contains(HostMessage.GoTo.Cfi("epubcfi(/6/6!/4)"))
            assertThat(viewModel.narration.following).isEqualTo(Following.On)
            assertThat(viewModel.state.value.goBackShown).isFalse()
        }

    @Test
    fun `a sentence missing from the section shown moves nothing, and following goes on`() =
        runTest(mainRule.dispatcher) {
            val harness = harness()
            val viewModel = harness.reading()
            harness.player.hear(0, "a1")

            harness.engine.emit(PageMessage.NarrationMissing(0, "a1"))

            assertThat(harness.engine.sent.filterIsInstance<HostMessage.GoTo>()).isEmpty()
            assertThat(viewModel.narration.following).isEqualTo(Following.On)
            assertThat(viewModel.narration.strip).isNull()
            // The next sentence the text has is underlined and followed as before.
            harness.player.hear(0, "a2")
            assertThat(harness.engine.sent.last()).isEqualTo(HostMessage.NarrationMark(0, "a2", false, true))
        }

    @Test
    fun `a sentence missing while not following moves nothing`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(2, "c1")
        harness.engine.emit(PageMessage.Turning)

        harness.engine.emit(PageMessage.NarrationMissing(2, "c1"))

        assertThat(harness.engine.sent.filterIsInstance<HostMessage.GoTo>()).isEmpty()
    }

    @Test
    fun `a missing sentence's move to its section's start sends no text position`() = runTest(mainRule.dispatcher) {
        val harness = harness()
        harness.reading()
        harness.player.hear(2, "c1")

        harness.engine.emit(PageMessage.NarrationMissing(2, "c1"))
        harness.engine.emit(pageAt(cfi = ThirdCfi, fraction = 0.6, sectionIndex = 2).copy(byHost = true))
        advanceTimeBy(10.seconds)

        assertThat(harness.repository.savedPositions).isEmpty()
    }
}
