package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NarrationCursorTest {

    private val cursor = DefaultNarrationCursor()
    private val first = HeardSentence(BookId("4"), FileId("12"), 2, "ch1.xhtml#s3", "ch1.xhtml", playing = true)
    private val second = first.copy(fragment = "ch1.xhtml#s4")

    private fun TestScope.collectSentences(): MutableList<HeardSentence?> {
        val seen = mutableListOf<HeardSentence?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cursor.sentence.collect { seen += it } }
        return seen
    }

    @Test
    fun `nothing is heard and nothing has finished at first`() {
        assertThat(cursor.sentence.value).isNull()
        assertThat(cursor.finished.value).isNull()
    }

    @Test
    fun `a published sentence reaches a collector, an identical one is not told again`() = runTest {
        val seen = collectSentences()

        cursor.publish(first)
        cursor.publish(first.copy())
        cursor.publish(second)
        cursor.publish(second.copy(playing = false))
        cursor.publish(null)

        assertThat(seen).containsExactly(null, first, second, second.copy(playing = false), null).inOrder()
    }

    @Test
    fun `a new collector is given the latest sentence`() = runTest {
        cursor.publish(first)
        cursor.publish(second)

        val seen = collectSentences()

        assertThat(seen).containsExactly(second)
    }

    @Test
    fun `a skipped section reaches the collectors that are active, and is not replayed to a later one`() = runTest {
        val notice = SkippedNotice(BookId("4"), 3, "Interlude")
        cursor.announce(SkippedNotice(BookId("4"), 1, null))
        val early = mutableListOf<SkippedNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cursor.skipped.collect { early += it } }

        cursor.announce(notice)
        val late = mutableListOf<SkippedNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { cursor.skipped.collect { late += it } }
        runCurrent()

        assertThat(early).containsExactly(notice)
        assertThat(late).isEmpty()
    }

    @Test
    fun `a finished narration stays finished until another book is heard`() {
        cursor.publish(first)
        cursor.finish(BookId("4"))
        assertThat(cursor.finished.value).isEqualTo(BookId("4"))

        cursor.publish(second)
        cursor.publish(null)
        assertThat(cursor.finished.value).isEqualTo(BookId("4"))

        cursor.publish(first.copy(bookId = BookId("5")))
        assertThat(cursor.finished.value).isNull()
    }

    @Test
    fun `finishing nothing clears what had finished`() {
        cursor.finish(BookId("4"))

        cursor.finish(null)

        assertThat(cursor.finished.value).isNull()
    }
}
