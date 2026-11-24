package app.tfdn.lirelle.signout

import app.tfdn.lirelle.core.comics.di.ComicPagesProfileEntry
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.di.LibraryProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.marks.BookMarks
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.di.AppProfileEntry
import app.tfdn.lirelle.di.ProfileDataOnDevice
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first

/** What an account leaves on the device by using the app, written and read through the profile's real stores. */
internal interface Traces {
    /** Writes them, as the screens of the signed-in app do. */
    suspend fun leave()

    /** They are there: without this, finding nothing afterwards would prove nothing. */
    suspend fun assertLeft()

    /** The open profile has none of them, in its stores nor in the folders of any profile. */
    suspend fun assertNone()
}

internal val TracedBook = BookId("41")
private val TracedFile = FileId("7")
private val TracedSeries = SeriesId("4")
private const val Place = "epubcfi(/6/4!/4/2)"
private const val Percent = 0.4
private val CoverKey = "$TestServer/api/v1/books/$TracedBook/cover"

/**
 * What reading and browsing leave: the page a book was left at, a bookmark in it, a text searched, the
 * direction a series of comics is read in, a page of an album and a cover seen on a screen.
 */
internal class ReadingTraces(private val device: ProfileView) : Traces {

    private val series = DirectionScope.Series(TracedSeries)

    private fun reading() = device.profile<ReaderCoreProfileEntry>()

    private fun searches() = device.profile<LibraryProfileEntry>().recentSearches()

    private fun comics() = device.profile<ComicPagesProfileEntry>().comicSettingsStore()

    private fun covers() = checkNotNull(device.profile<AppProfileEntry>().coverImageLoader().diskCache)

    /** The covers kept on the disk, apart from the cache's own journal. */
    private fun coversKept() = device.filesIn("covers").filterNot { it.name.startsWith("journal") }

    override suspend fun leave() {
        reading().readingPositionStore().put(LocalPosition(TracedFile, Place, Percent, Instant.EPOCH))
        // Refused by the server of the test: the bookmark shows, and waits in the profile to be sent.
        reading().readerMarks().addBookmark(TracedBook, TracedFile, Place, "Chapter 2")
        searches().record("mars")
        comics().setDirection(series, Direction.RightToLeft)
        // A page of an album seen in the comics reader, where its store keeps them.
        val cache = device.profile<ProfileDataOnDevice>().profileFolders().cache
        File(cache, "comics/31-v1/0").apply { parentFile?.mkdirs() }.writeText("page")
        val covers = covers()
        checkNotNull(covers.openEditor(CoverKey)).apply {
            covers.fileSystem.write(data) { writeUtf8("cover") }
            covers.fileSystem.write(metadata) { writeUtf8("") }
            commit()
        }
    }

    override suspend fun assertLeft() {
        assertWithMessage("position").that(reading().readingPositionStore().get(TracedFile)?.cfi).isEqualTo(Place)
        assertWithMessage("bookmarks").that(reading().readerMarks().of(TracedBook).first().bookmarks).hasSize(1)
        assertWithMessage("searches").that(searches().entries.first()).containsExactly("mars")
        assertWithMessage("direction").that(comics().settings(series).first().direction)
            .isEqualTo(Direction.RightToLeft)
        assertWithMessage("comic pages").that(device.filesIn("comics")).hasSize(1)
        assertWithMessage("covers").that(coversKept()).isNotEmpty()
    }

    override suspend fun assertNone() {
        assertWithMessage("position").that(reading().readingPositionStore().get(TracedFile)).isNull()
        assertWithMessage("marks").that(reading().readerMarks().of(TracedBook).first()).isEqualTo(BookMarks())
        assertWithMessage("marks to send").that(reading().readerMarkOutbox().pending()).isEmpty()
        assertWithMessage("searches").that(searches().entries.first()).isEmpty()
        assertWithMessage("direction").that(comics().settings(series).first().direction)
            .isEqualTo(Direction.LeftToRight)
        assertWithMessage("comic pages").that(device.filesIn("comics")).isEmpty()
        assertWithMessage("covers").that(covers().size).isEqualTo(0)
        assertWithMessage("cover files").that(coversKept()).isEmpty()
    }
}
