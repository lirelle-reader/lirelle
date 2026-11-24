package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FetchResult
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.WaitReason
import app.tfdn.lirelle.core.downloads.model.isUpdating
import app.tfdn.lirelle.core.downloads.offline.RegistryDownloadedFiles
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val TheBook = BookId("1")

/** A kept format is fetched again beside its copy, and takes its place once whole. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRunTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun queueTest(test: suspend QueueFixture.(TestScope) -> Unit) = runTest {
        QueueFixture(this, folder.newFolder("downloads")).test(this)
    }

    /** Book 1 keeps an audiobook of two files; the server then has another version of the second. */
    private suspend fun QueueFixture.audiobookChanged() {
        serve(TheBook, Audiobook, audio("a1"), audio("a2"))
        queue.ask(TheBook, setOf(Audiobook))
        runner.runUntilIdle()
        serve(TheBook, Audiobook, audio("a1"), audio("a2", bytes = 70, version = "v2"), version = "v2")
    }

    /** Both files of the audiobook of book 1 are of [version] on the server. */
    private fun QueueFixture.serveBoth(version: String, first: Long, second: Long) = serve(
        TheBook,
        Audiobook,
        audio("a1", bytes = first, version = version),
        audio("a2", bytes = second, version = version),
        version = version,
    )

    @Test
    fun `only the files that changed are fetched, and they take the place of the old ones`() = queueTest {
        audiobookChanged()
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(updateFetcher.fetched).containsExactly("1/Audiobook/a2")
        assertThat(fetcher.fetched).containsExactly("1/Audiobook/a1", "1/Audiobook/a2")
        val record = record(TheBook, Audiobook)
        assertThat(record.state).isEqualTo(FormatState.Complete(170))
        assertThat(record.version).isEqualTo("v2")
        assertThat(record.files).containsExactly(audio("a1"), audio("a2", bytes = 70, version = "v2")).inOrder()
        assertThat(record.update).isNull()
        assertThat(file(TheBook, Audiobook, "a2").length()).isEqualTo(70)
        assertThat(besideNames(TheBook)).isEmpty()
        assertThat(checkNotNull(registry.book(TheBook)).isUpdating).isFalse()
    }

    @Test
    fun `a file the new version no longer has is deleted at the swap`() = queueTest {
        audiobookChanged()
        serve(TheBook, Audiobook, audio("a1"), audio("a3", bytes = 20, version = "v2"), version = "v2")
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(file(TheBook, Audiobook, "a2").exists()).isFalse()
        assertThat(file(TheBook, Audiobook, "a3").length()).isEqualTo(20)
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(120))
    }

    @Test
    fun `while the book is open the new files wait beside the kept copy, which is what is served`() = queueTest {
        serve(TheBook, Text, epub())
        queue.ask(TheBook, setOf(Text))
        runner.runUntilIdle()
        serve(TheBook, Text, epub(bytes = 55, version = "v2"), version = "v2")
        remover.hold(TheBook)
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(beside(TheBook, Text, "12.epub").length()).isEqualTo(55)
        assertThat(beside(TheBook, Text, "12.epub")).isNotEqualTo(file(TheBook, Text, "12.epub"))
        val record = record(TheBook, Text)
        assertThat(record.state).isEqualTo(FormatState.Complete(40))
        assertThat(record.version).isEqualTo("v1")
        assertThat(record.update?.state).isEqualTo(FormatState.Complete(55))
        val kept = RegistryDownloadedFiles(registry, files, locations).text(TheBook, FileId("12"))
        assertThat(kept?.length()).isEqualTo(40)
        assertThat(queue.queue.first().active).isNull()
        assertThat(queue.queue.first().waiting).isEmpty()
    }

    @Test
    fun `the swap is done when the last reader or player lets go of the book`() = queueTest {
        audiobookChanged()
        remover.hold(TheBook)
        remover.hold(TheBook)
        askUpdate(TheBook)
        runner.runUntilIdle()

        remover.release(TheBook)
        it.runCurrent()
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v1")
        assertThat(file(TheBook, Audiobook, "a2").length()).isEqualTo(100)

        remover.release(TheBook)
        it.runCurrent()
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v2")
        assertThat(record(TheBook, Audiobook).update).isNull()
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(170))
        assertThat(file(TheBook, Audiobook, "a2").length()).isEqualTo(70)
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `an update fetched whole and not swapped when the application stopped is swapped at the next start`() =
        queueTest {
            audiobookChanged()
            remover.hold(TheBook)
            askUpdate(TheBook)
            runner.runUntilIdle()
            val fresh = restartedAs(QueueFixture(it, files.bookDir(TheBook).parentFile!!))

            fresh.runner.runUntilIdle()

            assertThat(fresh.record(TheBook, Audiobook).version).isEqualTo("v2")
            assertThat(fresh.state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(170))
            assertThat(fresh.file(TheBook, Audiobook, "a2").length()).isEqualTo(70)
            assertThat(fresh.besideNames(TheBook)).isEmpty()
        }

    @Test
    fun `a swap stopped half way is finished at the next start`() = queueTest {
        audiobookChanged()
        serveBoth("v2", first = 60, second = 70)
        remover.hold(TheBook)
        askUpdate(TheBook)
        runner.runUntilIdle()
        // The first file was moved, and the process ended there.
        beside(TheBook, Audiobook, "a1").copyTo(file(TheBook, Audiobook, "a1"), overwrite = true)
        beside(TheBook, Audiobook, "a1").delete()
        val fresh = restartedAs(QueueFixture(it, files.bookDir(TheBook).parentFile!!))

        fresh.runner.runUntilIdle()

        assertThat(fresh.state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(130))
        assertThat(fresh.record(TheBook, Audiobook).version).isEqualTo("v2")
        assertThat(fresh.file(TheBook, Audiobook, "a1").length()).isEqualTo(60)
        assertThat(fresh.file(TheBook, Audiobook, "a2").length()).isEqualTo(70)
    }

    @Test
    fun `the EPUB the text and the narration share is fetched and swapped once, and both follow`() = queueTest {
        serve(TheBook, Text, epub())
        serve(TheBook, Narration, epub(), audio("t1", bytes = 30))
        queue.ask(TheBook, setOf(Narration))
        runner.runUntilIdle()
        val epub = epub(bytes = 55, version = "v2")
        val track = audio("t1", bytes = 35, version = "v2")
        serve(TheBook, Text, epub, version = "v2")
        serve(TheBook, Narration, epub, track, version = "v2")
        remover.hold(TheBook)
        askUpdate(TheBook)

        runner.runUntilIdle()
        assertThat(updateFetcher.fetched).containsExactly("1/Text/12.epub", "1/Narration/t1").inOrder()
        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(40)

        remover.release(TheBook)
        it.runCurrent()
        assertThat(file(TheBook, Narration, "12.epub").length()).isEqualTo(55)
        assertThat(record(TheBook, Text).files).containsExactly(epub)
        assertThat(record(TheBook, Narration).files).containsExactly(epub, track).inOrder()
        assertThat(state(TheBook, Text)).isEqualTo(FormatState.Complete(55))
        assertThat(state(TheBook, Narration)).isEqualTo(FormatState.Complete(35))
        assertThat(record(TheBook, Text).update).isNull()
        assertThat(record(TheBook, Narration).update).isNull()
        assertThat(besideNames(TheBook)).isEmpty()
    }

    @Test
    fun `a narration updated alone takes the text's record with the EPUB they share`() = queueTest {
        serve(TheBook, Text, epub())
        serve(TheBook, Narration, epub(), audio("t1", bytes = 30))
        queue.ask(TheBook, setOf(Narration))
        runner.runUntilIdle()
        val epub = epub(bytes = 55, version = "v2")
        serve(TheBook, Narration, epub, audio("t1", bytes = 30), version = "v2")
        askUpdate(TheBook)

        runner.runUntilIdle()

        assertThat(updateFetcher.fetched).containsExactly("1/Narration/12.epub")
        assertThat(record(TheBook, Narration).version).isEqualTo("v2")
        assertThat(record(TheBook, Text).files).containsExactly(epub)
        assertThat(file(TheBook, Text, "12.epub").length()).isEqualTo(55)
    }

    @Test
    fun `a version that moves while the update is fetched starts it again on the newest`() = queueTest {
        audiobookChanged()
        serveBoth("v2", first = 60, second = 70)
        updateFetcher.on("a2") { FetchResult.Cut(0, ApiError.ServerUnreachable) }
        askUpdate(TheBook)
        keepRunning()
        it.runCurrent()
        assertThat(update(TheBook, Audiobook)?.state).isEqualTo(FormatState.Waiting(WaitReason.NoNetwork, 60, 130))

        serveBoth("v3", first = 50, second = 45)
        it.advanceTimeBy(5_000)
        it.runCurrent()

        assertThat(updateFetcher.fetched)
            .containsExactly("1/Audiobook/a1", "1/Audiobook/a2", "1/Audiobook/a1", "1/Audiobook/a2").inOrder()
        assertThat(record(TheBook, Audiobook).version).isEqualTo("v3")
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(95))
        assertThat(file(TheBook, Audiobook, "a1").length()).isEqualTo(50)
    }

    @Test
    fun `a server back to the kept version has nothing to update`() = queueTest {
        audiobookChanged()
        askUpdate(TheBook)
        serve(TheBook, Audiobook, audio("a1"), audio("a2"))

        runner.runUntilIdle()

        assertThat(updateFetcher.fetched).isEmpty()
        assertThat(record(TheBook, Audiobook).update).isNull()
        assertThat(state(TheBook, Audiobook)).isEqualTo(FormatState.Complete(200))
    }
}
