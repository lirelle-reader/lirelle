package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a text is named by is known from the asking, before the server is asked what it is made of. */
@OptIn(ExperimentalCoroutinesApi::class)
class AskedNameTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val book = BookId("1")

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    private suspend fun QueueFixture.record(bookId: BookId, format: DownloadFormat) =
        checkNotNull(registry.book(bookId)).formats.first { it.format == format }

    @Test
    fun `a comic that waits for a network before it is planned is named by its file`() = queueTest {
        serveAlbum(book, 4)
        network.value = Mobile

        queue.ask(book, setOf(Text), mapOf(Text to 5_500), TextName.Cbz)
        runner.runUntilIdle()

        assertThat(plans.asked).isEmpty()
        assertThat(state(book, Text)).isEqualTo(FormatState.Waiting(WaitReason.UnmeteredOnly, 0, 5_500))
        assertThat(text(book).textName).isEqualTo(TextName.Cbz)
        assertThat(queue.queue.first().waiting.single().textName).isEqualTo(TextName.Cbz)
    }

    @Test
    fun `the name asked is the text's alone, and a narration's text has it too`() = queueTest {
        queue.ask(book, setOf(Narration, Audiobook), emptyMap(), TextName.Pdf)

        assertThat(record(book, Text).textName).isEqualTo(TextName.Pdf)
        assertThat(record(book, Audiobook).textName).isEqualTo(TextName.Text)
        assertThat(record(book, Narration).textName).isEqualTo(TextName.Text)
    }

    @Test
    fun `asked with no name a text is the text, and the plan says its name later`() = queueTest {
        serveAlbum(book, 4)
        network.value = NoNetwork
        queue.ask(book, setOf(Text))
        assertThat(text(book).textName).isEqualTo(TextName.Text)

        network.value = Wifi
        runner.runUntilIdle()

        assertThat(text(book).textName).isEqualTo(TextName.Cbz)
    }

    @Test
    fun `a text already asked keeps the name it has`() = queueTest {
        network.value = NoNetwork
        queue.ask(book, setOf(Text), emptyMap(), TextName.Djvu)

        queue.ask(book, setOf(Text, Audiobook), emptyMap(), TextName.Text)

        assertThat(text(book).textName).isEqualTo(TextName.Djvu)
    }
}
