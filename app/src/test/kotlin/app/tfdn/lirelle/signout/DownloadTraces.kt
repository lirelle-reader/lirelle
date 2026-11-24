package app.tfdn.lirelle.signout

import app.tfdn.lirelle.core.downloads.di.DownloadsProfileEntry
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.updateFile
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.di.ProfileDataOnDevice
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlinx.coroutines.flow.first

private const val AudioBytes = 300
private const val UpdateBytes = 120
private const val FilesKept = 4

/**
 * What keeping a book on the device leaves: its record, its files, its page and its cover, and the hour the
 * kept books were last checked. The server has a newer version of the book, half fetched beside the kept
 * one; the book was since found gone from the server, and is to be deleted when it closes.
 */
internal class DownloadTraces(private val device: ProfileView) : Traces {

    /** The downloads of the open profile, as the screens reach them. */
    private fun downloads() = device.profile<DownloadsProfileEntry>()

    /** What no screen asks of them: the registry itself, what runs the queue, the hour of the last check. */
    private fun kept() = device.profile<ProfileDataOnDevice>()

    private fun audio(version: String, bytes: Int) =
        PlannedFile("a1", FileSource.AudioAsset("a1"), version, bytes.toLong(), resumable = true, FileKind.Audio)

    private fun book(): DownloadedBook {
        val update = FormatUpdate("v2", listOf(audio("v2", UpdateBytes)), UpdateBytes.toLong())
        val record = FormatRecord(
            format = DownloadFormat.Audiobook,
            state = FormatState.Complete(AudioBytes.toLong()),
            files = listOf(audio("v1", AudioBytes)),
            fileId = null,
            version = "v1",
            askedAt = Instant.EPOCH,
            update = update,
        )
        return DownloadedBook(TracedBook, listOf(record), Instant.EPOCH, null, gone = true, deleteAtClose = true)
    }

    override suspend fun leave() {
        // As it starts, the profile puts its downloads right on its own, once: a book left to delete when it
        // closes goes then. The book of the test is kept after that, so that it is still there to sign out of.
        kept().downloadRunner().runUntilIdle()
        kept().downloadRegistry().update(TracedBook) { book() }
        val files = downloads().downloadFiles()
        files.file(TracedBook, DownloadFormat.Audiobook, "a1").apply { parentFile?.mkdirs() }
            .writeBytes(ByteArray(AudioBytes))
        files.updateFile(TracedBook, DownloadFormat.Audiobook, "a1").apply { parentFile?.mkdirs() }
            .writeBytes(ByteArray(UpdateBytes / 2))
        files.kept(TracedBook, "detail.json").writeText("{}")
        files.kept(TracedBook, "cover").writeText("cover")
        kept().downloadCheckClock().mark(Instant.EPOCH)
    }

    override suspend fun assertLeft() {
        assertWithMessage("queue").that(downloads().downloadsQueue().books.first().map { it.bookId })
            .containsExactly(TracedBook)
        assertWithMessage("record").that(kept().downloadRegistry().books.first()).containsExactly(book())
        assertWithMessage("files").that(device.filesIn("downloads")).hasSize(FilesKept)
        val updates = device.filesIn("downloads").filter { "/${TracedBook.fileName}/update/" in it.path }
        assertWithMessage("update").that(updates).hasSize(1)
        assertWithMessage("check hour").that(kept().downloadCheckClock().last()).isEqualTo(Instant.EPOCH)
    }

    override suspend fun assertNone() {
        assertWithMessage("queue").that(downloads().downloadsQueue().books.first()).isEmpty()
        assertWithMessage("records").that(kept().downloadRegistry().books.first()).isEmpty()
        assertWithMessage("files").that(device.filesIn("downloads")).isEmpty()
        // Nor the hour its books were last checked: its first pass is a new one.
        assertWithMessage("check hour").that(kept().downloadCheckClock().last()).isNull()
    }
}
