package app.tfdn.lirelle.signout

import app.tfdn.lirelle.core.audio.di.AudioProfileEntry
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.player.service.LastBook
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first

/** The speed the account chose for its book: the device keeps it as the speed of the next new book. */
internal const val BookSpeed = 1.5f
private const val HeardMs = 60_000L

/**
 * What listening leaves: the place an audiobook was left at, a bookmark in it, the speed chosen for it, the
 * last book of the player, the map of a book read along with its narration, and the audio streamed.
 */
internal class ListeningTraces(private val device: ProfileView) : Traces {

    private val lastBook = LastBook(TracedBook, "Mars", coverUrl = null)

    private fun listening() = device.profile<AudioProfileEntry>()

    private fun kept() = device.profile<ProfileDataOnDevice>()

    private fun lastBookMark() = device.profile<PlayerProfileEntry>().lastBookMark()

    /** What the profiles keep of the audio they streamed, and the index that names it. */
    private fun audioKept(): List<File> = device.stores().filter { it.name.startsWith("audio") }

    override suspend fun leave() {
        listening().audioPlaybackStore()
            .put(LocalPlayback(TracedBook, "a1", HeardMs, HeardMs, Instant.EPOCH, contentVersion = "m1"))
        // Refused by the server of the test: the bookmark shows, and waits in the profile to be sent.
        listening().audioBookmarks().add(TracedBook, HeardMs, chapterId = null, title = "A place")
        kept().playerSettingsStore().setSpeed(TracedBook, BookSpeed)
        kept().lastBookStore().put(lastBook)
        // The map of a read-along book, where its store keeps them: it holds the text of the book.
        File(kept().profileFolders().files, "readalong/7-none.map.gz").apply { parentFile?.mkdirs() }.writeText("map")
        // The player's cache, opened as playing a book opens it: its folder and its index are made. It makes
        // them on a thread of its own; asking it anything waits for that.
        kept().audioCache().cache.cacheSpace
    }

    override suspend fun assertLeft() {
        assertWithMessage("place").that(listening().audioPlaybackStore().get(TracedBook)?.positionMs)
            .isEqualTo(HeardMs)
        assertWithMessage("bookmarks").that(listening().audioBookmarks().of(TracedBook).first()).hasSize(1)
        assertWithMessage("speed").that(kept().playerSettingsStore().speedOf(TracedBook)).isEqualTo(BookSpeed)
        assertWithMessage("last book").that(kept().lastBookStore().get()).isEqualTo(lastBook)
        assertWithMessage("last book's mark").that(lastBookMark().exists()).isTrue()
        assertWithMessage("read-along maps").that(device.filesIn("readalong")).hasSize(1)
        assertWithMessage("audio").that(audioKept().map { it.name }).containsAtLeast("audio", "audio.db")
    }

    override suspend fun assertNone() {
        assertWithMessage("place").that(listening().audioPlaybackStore().get(TracedBook)).isNull()
        assertWithMessage("bookmarks").that(listening().audioBookmarks().of(TracedBook).first()).isEmpty()
        assertWithMessage("bookmarks to send").that(listening().audioBookmarkQueue().pending()).isEmpty()
        assertWithMessage("speed").that(kept().playerSettingsStore().speedOf(TracedBook)).isNull()
        assertWithMessage("last book").that(kept().lastBookStore().get()).isNull()
        assertWithMessage("last book's mark").that(lastBookMark().exists()).isFalse()
        assertWithMessage("read-along maps").that(device.filesIn("readalong")).isEmpty()
        assertWithMessage("audio").that(audioKept()).isEmpty()
    }
}
