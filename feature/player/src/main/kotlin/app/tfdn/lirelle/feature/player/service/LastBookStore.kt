@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The book last loaded in the player, for a play button pressed after the service ended. */
data class LastBook(val bookId: BookId, val title: String, val coverUrl: String?)

/**
 * Remembers the [LastBook] of a profile. Forgotten when the user closes the book, kept when the profile closes
 * with the book loaded ([LastBookKeeper]); removing the profile takes it away with everything the profile kept.
 */
interface LastBookStore {
    suspend fun get(): LastBook?

    suspend fun put(book: LastBook)

    suspend fun clear()
}

/**
 * The names the last book of a profile is kept under, in its folder, and where an installation from before
 * the profiles kept its one last book: the migration moves it by these, so that it cannot look for a name the
 * store does not use.
 */
object LastBookFiles {
    /** The profile's preferences file of its last book. */
    const val Store = "last_book"

    /** The file that is there while the profile keeps a last book ([LastBookMark]). */
    const val Mark = "last_book.kept"

    /** The shared preferences of the application that held the last book, whoever it belonged to. */
    const val OldPreferences = "player_last_book"
}

/**
 * Whether the profile keeps a last book, for a play button's receiver: it must answer at once, in a process
 * that may have just started for the button, and the book is in a store that is read in its own time. So the
 * answer is a file that is there or not, beside the store, in the profile's folder: it goes with the profile.
 */
class LastBookMark(private val file: File) {

    /** Whether a last book is kept. */
    fun exists(): Boolean = file.exists()

    internal fun put() {
        file.parentFile?.mkdirs()
        file.createNewFile()
    }

    internal fun remove() {
        file.delete()
    }
}

/**
 * The last book in a file of the profile's own (`last_book`), which goes with the profile's folders: nothing
 * forgets it by hand when the account leaves. It is one profile's for its whole life, so nothing in it says
 * whose it is.
 *
 * @param mark there while a book is kept: written after the book and removed before it, so that it never
 *   outlives one for long. A mark that stands for no book only starts a service that ends at once.
 * @param io where the mark's file is touched.
 */
class DataStoreLastBookStore(
    private val store: DataStore<Preferences>,
    private val mark: LastBookMark,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LastBookStore {

    override suspend fun get(): LastBook? = store.data.first().lastBook()

    override suspend fun put(book: LastBook) {
        store.edit { stored ->
            // The file holds one book: what the one before left goes, the cover it had included.
            stored.clear()
            // A preference holds no id: its text.
            stored[Book] = book.bookId.value
            stored[Title] = book.title
            book.coverUrl?.let { stored[Cover] = it }
        }
        withContext(io) { mark.put() }
    }

    override suspend fun clear() {
        withContext(io) { mark.remove() }
        store.edit { it.clear() }
    }

    /** A book kept as a number, before the ids were text, is none: [Book] would not read it. */
    private fun Preferences.lastBook(): LastBook? {
        val bookId = (asMap()[Book] as? String)?.let(::BookId) ?: return null
        return LastBook(bookId = bookId, title = this[Title].orEmpty(), coverUrl = this[Cover])
    }
}

/** The names the last book is kept under: the same as in the shared preferences of before the profiles. */
internal const val BookName = "book"
internal const val TitleName = "title"
internal const val CoverName = "cover"

/**
 * Under these, the shared preferences of before the profiles said whose the last book was. The migration reads
 * them there to take only the account's own book ([moveLastBook]); nothing writes them in a profile's file.
 */
private const val OldServerName = "server"
private const val OldUserName = "user"

private val Book = stringPreferencesKey(BookName)
private val Title = stringPreferencesKey(TitleName)
private val Cover = stringPreferencesKey(CoverName)

/**
 * Writes the last book of an installation from before the profiles into the store of a profile, with the mark
 * that says there is one: without the mark, a play button pressed while nothing plays finds no book. For the
 * migration, which deletes the old preferences afterwards: interrupted before that, it does the same again.
 *
 * Only the book kept for the user [userId] of [serverUrl] is taken: the old store gave another account's to
 * nobody, and it must not become this profile's. The server and the user are checked here and not written:
 * the profile's file is one account's for its whole life, and says nothing of whose it is.
 *
 * @param old the shared preferences that held it ([LastBookFiles.OldPreferences]), where the user was a number.
 * @param store the profile's own file for it.
 * @param userId the account's id as the app names it, as text.
 */
suspend fun moveLastBook(
    old: SharedPreferences,
    store: DataStore<Preferences>,
    mark: LastBookMark,
    serverUrl: String,
    userId: String,
) {
    val kept = old.all
    val book = kept[BookName] as? Long ?: return
    if (kept[OldServerName] != serverUrl || (kept[OldUserName] as? Long)?.toString() != userId) return
    store.edit { stored ->
        // The old store's number was the server's id: its text is the book's id.
        stored[Book] = book.toString()
        stored[Title] = (kept[TitleName] as? String).orEmpty()
        (kept[CoverName] as? String)?.let { stored[Cover] = it }
    }
    mark.put()
}

/**
 * What a play button with nothing loaded gets: the book kept, so that the session always shows it as starting
 * and enters the foreground in time (the service was started for it); that it still is the last book is
 * checked when it is loaded ([BookSession.resumable]), and one that no longer is ends in an empty player.
 * Null when no book is kept, and when the record cannot be read: there is no book to resume, and a play
 * button's start that owed the foreground is settled by [ForegroundDebt]'s check.
 */
suspend fun resumptionItems(store: LastBookStore): MediaSession.MediaItemsWithStartPosition? {
    val book = runCatching { store.get() }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        null
    } ?: return null
    return MediaSession.MediaItemsWithStartPosition(listOf(PlayerSessionContract.resumeItem(book)), 0, C.TIME_UNSET)
}

/**
 * The last book, for a play button pressed while nothing is loaded ([resumptionItems]), as the session asks
 * for it: read in [scope], the service's.
 *
 * The session waits for the answer, so there always is one: however the reading ends, without having
 * answered, there is no book to resume. It ends so when the profile's store closes under it, which cancels
 * whoever reads it, and when [scope] has ended before it could start.
 *
 * @param store null when the service has no profile to play for any more: there is no book to resume.
 */
fun resumption(
    store: LastBookStore?,
    scope: CoroutineScope,
): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
    val noBook = { future.setException(UnsupportedOperationException("No book to resume")) }
    val reading = scope.launch {
        val items = store?.let { resumptionItems(it) }
        if (items == null) noBook() else future.set(items)
    }
    // Does nothing to a future that was answered.
    reading.invokeOnCompletion { noBook() }
    return future
}
