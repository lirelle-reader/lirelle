package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.DefaultNarrationCursor
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.feature.player.controller.NarrationCommands
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.service.NarrationStart
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The player's narration commands as the reader uses them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerNarrationPlayerTest {

    private class FakeNarrationCommands : NarrationCommands {
        override val narration = MutableStateFlow<NarrationUi?>(null)
        override val skipped = MutableSharedFlow<SkippedNotice>()
        override val narrationFailure = MutableStateFlow<NarrationFailure?>(null)
        val asked = mutableListOf<String>()

        override fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean) {
            asked += "narrate $bookId $fileId $start $play"
        }

        override fun previousSentence() {
            asked += "previousSentence"
        }

        override fun nextSentence() {
            asked += "nextSentence"
        }

        override fun narrationSpeed(speed: Float) {
            asked += "narrationSpeed $speed"
        }

        override fun pauseNarration() {
            asked += "pauseNarration"
        }

        override fun playNarration() {
            asked += "playNarration"
        }

        override fun retryNarration() {
            asked += "retryNarration"
        }

        override fun goToSection(index: Int) {
            asked += "goToSection $index"
        }

        override fun goBackInNarration() {
            asked += "goBackInNarration"
        }
    }

    private val commands = FakeNarrationCommands()
    private val cursor = DefaultNarrationCursor()
    private val running = MutableStateFlow(false)
    private val player = ControllerNarrationPlayer(commands, cursor, running, RecordingNarrationActions())

    @Test
    fun `the narration is watched only while the service runs, a narration started later included`() = runTest {
        val seen = mutableListOf<NarrationUi?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.narration.collect { seen += it } }
        commands.narration.value = narrationUi()

        assertThat(seen).containsExactly(null)
        assertThat(commands.narration.subscriptionCount.value).isEqualTo(0)

        running.value = true

        assertThat(seen).containsExactly(null, narrationUi()).inOrder()

        running.value = false

        assertThat(seen.last()).isNull()
        assertThat(commands.narration.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun `pause is the player's pause, and narrate passes through playing`() {
        player.pause()
        player.narrate(NarratedBook, TextFile, NarrationStart.AtSentence(0, "a1"))

        val narrated = "narrate $NarratedBook $TextFile ${NarrationStart.AtSentence(0, "a1")} true"
        assertThat(commands.asked).containsExactly("pauseNarration", narrated).inOrder()
    }

    @Test
    fun `play is the player's play`() {
        player.play()

        assertThat(commands.asked).containsExactly("playNarration")
    }

    @Test
    fun `a narration's failure is watched only while the service runs`() = runTest {
        val seen = mutableListOf<NarrationFailure?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.failure.collect { seen += it } }
        commands.narrationFailure.value = NarrationFailure(NarratedBook, TextFile)

        // Watching the controller's failure would connect to the service, and start it.
        assertThat(seen).containsExactly(null)
        assertThat(commands.narrationFailure.subscriptionCount.value).isEqualTo(0)

        running.value = true

        assertThat(seen).containsExactly(null, NarrationFailure(NarratedBook, TextFile)).inOrder()

        running.value = false

        assertThat(seen.last()).isNull()
        assertThat(commands.narrationFailure.subscriptionCount.value).isEqualTo(0)
    }
}
