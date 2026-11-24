package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FormatPlan
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.hasUpdate
import app.tfdn.lirelle.core.downloads.model.updateBytes
import app.tfdn.lirelle.core.downloads.store.DataStores
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

private val Book1 = BookId("1")
private val Book2 = BookId("2")
private val Book3 = BookId("3")

/** A book the device does not keep. */
private val UnknownBook = BookId("5")

class UpdateCheckTest {

    @get:Rule
    val dataStores = DataStores()

    private fun checkTest(test: suspend UpdateFixture.() -> Unit) = runBlocking { UpdateFixture(dataStores).test() }

    @Test
    fun `an EPUB the server changed has an update of its size`() = checkTest {
        keep(Book1, complete(Text, epub()))
        val changed = epub(bytes = 55, version = "v2")
        serve(Book1, Text, changed, version = "v2")

        updates.check()

        assertThat(update(Book1, Text))
            .isEqualTo(FormatUpdate("v2", listOf(changed), bytes = 55, textName = TextName.Text))
        assertThat(book(Book1).hasUpdate).isTrue()
    }

    @Test
    fun `a manifest with one changed track gives that track's bytes`() = checkTest {
        keep(Book1, complete(Audiobook, audio("a1"), audio("a2", bytes = 300), version = "r1"))
        val files = listOf(audio("a1"), audio("a2", bytes = 320, version = "v2"))
        serve(Book1, Audiobook, *files.toTypedArray(), version = "r2")

        updates.check()

        assertThat(update(Book1, Audiobook)).isEqualTo(FormatUpdate("r2", files, bytes = 320))
    }

    @Test
    fun `a file the new version adds is to fetch, one of unknown size counts for nothing`() = checkTest {
        keep(Book1, complete(Audiobook, audio("a1"), version = "r1"))
        serve(Book1, Audiobook, audio("a1"), audio("a2", bytes = 70), audio("a3", bytes = null), version = "r2")

        updates.check()

        assertThat(update(Book1, Audiobook)?.bytes).isEqualTo(70)
    }

    @Test
    fun `the EPUB a narration shares with the text is counted once`() = checkTest {
        keep(Book1, complete(Text, epub()), complete(Narration, epub(), audio("t1", version = "12:v1")))
        serve(Book1, Text, epub(bytes = 55, version = "v2"), version = "v2")
        val track = audio("t1", bytes = 90, version = "12:v2")
        serve(Book1, Narration, epub(bytes = 55, version = "v2"), track, version = "v2")

        updates.check()

        assertThat(update(Book1, Narration)?.bytes).isEqualTo(90)
        assertThat(update(Book1, Narration)?.files).hasSize(2)
        assertThat(book(Book1).updateBytes).isEqualTo(145)
    }

    @Test
    fun `an unchanged format has no update`() = checkTest {
        keep(Book1, complete(Text, epub()))
        serve(Book1, Text, epub())

        updates.check()

        assertThat(update(Book1, Text)).isNull()
        assertThat(book(Book1).gone).isFalse()
    }

    @Test
    fun `an unchanged text takes the name the server says of its file`() = checkTest {
        // Kept under no name of its own, and one its file's name does not tell.
        val file = epub("14.book").copy(kind = FileKind.Book)
        keep(Book1, complete(Text, file), complete(Audiobook, audio("a1")))
        plans.answers[Book1 to Text] = ApiResult.Success(FormatPlan(FileId("14"), "v1", listOf(file), TextName.Pdf))
        serve(Book1, Audiobook, audio("a1"))
        check(book(Book1).formats.all { it.textName == TextName.Text })

        updates.check()

        assertThat(book(Book1).formats.map { it.textName }).containsExactly(TextName.Pdf, TextName.Text).inOrder()
        assertThat(update(Book1, Text)).isNull()
    }

    @Test
    fun `an update whose version is no longer the server's is replaced`() = checkTest {
        keep(Book1, complete(Text, epub(), update = FormatUpdate("v2", listOf(epub(version = "v2")), 40)))
        val newest = epub(bytes = 60, version = "v3")
        serve(Book1, Text, newest, version = "v3")

        updates.check()

        assertThat(update(Book1, Text))
            .isEqualTo(FormatUpdate("v3", listOf(newest), bytes = 60, textName = TextName.Text))
    }

    @Test
    fun `a server back to the kept version clears the update`() = checkTest {
        keep(Book1, complete(Text, epub(), update = FormatUpdate("v2", listOf(epub(version = "v2")), 40)))
        serve(Book1, Text, epub())

        updates.check()

        assertThat(update(Book1, Text)).isNull()
    }

    @Test
    fun `a book the server no longer has is gone, and is not once found again`() = checkTest {
        keep(Book1, complete(Text, epub()))
        fail(Book1, Text, ApiError.NotFound)

        updates.check()
        assertThat(book(Book1).gone).isTrue()

        serve(Book1, Text, epub())
        updates.check(force = true)
        assertThat(book(Book1).gone).isFalse()
    }

    @Test
    fun `a book found gone loses the updates that were not started, and keeps those that were`() = checkTest {
        val waiting = FormatUpdate("v2", listOf(epub(version = "v2")), 40)
        val failure = FormatState.Failed(DownloadFailure.Damaged, 0)
        val failed = FormatUpdate("v2", listOf(audio("a1", version = "v2")), 100, failure)
        keep(Book1, complete(Text, epub(), update = waiting), complete(Audiobook, audio("a1"), update = failed))
        fail(Book1, Text, ApiError.NotFound)
        canDownload.value = false

        updates.check()

        assertThat(book(Book1).gone).isTrue()
        assertThat(update(Book1, Text)).isNull()
        assertThat(update(Book1, Audiobook)).isEqualTo(failed)
    }

    @Test
    fun `a book that lost one format but still answers for another is not gone`() = checkTest {
        keep(Book1, complete(Text, epub()), complete(Audiobook, audio("a1")))
        serve(Book1, Text, epub())
        fail(Book1, Audiobook, ApiError.NotFound)

        updates.check()

        assertThat(book(Book1).gone).isFalse()
    }

    @Test
    fun `a format being updated, or whose update failed, is left alone`() = checkTest {
        val running = FormatUpdate("v2", listOf(epub(version = "v2")), 40, FormatState.Downloading(10, 40))
        val failure = FormatState.Failed(DownloadFailure.Damaged, 0)
        val failed = FormatUpdate("r2", listOf(audio("a1", version = "v2")), 100, failure)
        keep(Book1, complete(Text, epub(), update = running), complete(Audiobook, audio("a1"), update = failed))
        serve(Book1, Text, epub(version = "v3"), version = "v3")
        serve(Book1, Audiobook, audio("a1"))

        updates.check()

        assertThat(update(Book1, Text)).isEqualTo(running)
        assertThat(update(Book1, Audiobook)).isEqualTo(failed)
        assertThat(plans.asked).isEmpty()
    }

    @Test
    fun `a format not complete, one being deleted and a book deleted at close are left alone`() = checkTest {
        keep(Book1, complete(Text, epub()).copy(state = FormatState.Downloading(10, 40)))
        keep(Book2, complete(Text, epub()).copy(removing = true))
        keep(Book3, complete(Text, epub())) { it.copy(deleteAtClose = true) }
        listOf(Book1, Book2, Book3).forEach { serve(it, Text, epub(version = "v2"), version = "v2") }

        updates.check()

        listOf(Book1, Book2, Book3).forEach { assertThat(update(it, Text)).isNull() }
        assertThat(plans.asked).isEmpty()
    }

    @Test
    fun `without the permission only gone books are looked for, and recorded updates stay`() = checkTest {
        canDownload.value = false
        val recorded = FormatUpdate("v2", listOf(epub(version = "v2")), 40)
        keep(Book1, complete(Text, epub()))
        keep(Book2, complete(Text, epub(), update = recorded))
        keep(Book3, complete(Text, epub()))
        serve(Book1, Text, epub(version = "v2"), version = "v2")
        serve(Book2, Text, epub())
        fail(Book3, Text, ApiError.NotFound)

        updates.check()

        assertThat(update(Book1, Text)).isNull()
        assertThat(update(Book2, Text)).isEqualTo(recorded)
        assertThat(book(Book3).gone).isTrue()
    }

    @Test
    fun `a failure that is not a missing book changes nothing`() = checkTest {
        val recorded = FormatUpdate("v2", listOf(epub(version = "v2")), 40)
        keep(Book1, complete(Text, epub(), update = recorded)) { it.copy(gone = true) }
        keep(Book2, complete(Text, epub()))
        fail(Book1, Text, ApiError.ServerUnreachable)
        fail(Book2, Text, ApiError.Moved(BookId("9")))
        val before = listOf(book(Book1), book(Book2))

        updates.check()

        assertThat(listOf(book(Book1), book(Book2))).isEqualTo(before)
    }

    @Test
    fun `one book is checked whatever the hour, and only it`() = checkTest {
        keep(Book1, complete(Text, epub()))
        keep(Book2, complete(Text, epub()))
        serve(Book1, Text, epub(version = "v2"), version = "v2")
        serve(Book2, Text, epub(version = "v2"), version = "v2")
        updates.check()
        plans.asked.clear()
        serve(Book1, Text, epub(version = "v3"), version = "v3")

        updates.checkBook(Book1)
        updates.checkBook(UnknownBook)

        assertThat(plans.asked).containsExactly(Book1 to Text)
        assertThat(update(Book1, Text)?.version).isEqualTo("v3")
        assertThat(registry.book(UnknownBook)).isNull()
    }
}
