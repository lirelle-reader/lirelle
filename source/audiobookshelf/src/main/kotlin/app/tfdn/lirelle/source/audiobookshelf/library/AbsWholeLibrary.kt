package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibraryDto
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the profile keeps of its server: its book libraries (for their order and the shape of their covers), their
 * genres' names as they write them ([genreNames]) and, once a list is narrowed on the device, each library's whole
 * list of items (`limit=0`), so that another filter on the same library asks nothing. A big library makes the first
 * filter slower (721 items are 1.4 MB, 0.4 s on the test server); that is accepted.
 *
 * The libraries are kept [LibrariesFreshness] long by the clock [now], then read again, so that a library added or
 * removed on the server shows, or stops being asked, without the profile being opened again; the selector's own read
 * of them ([readLibraries]) replaces what is kept. A whole list is kept until a pull to refresh of a list of its
 * library reads it again, or a pull to refresh of the library's list the server pages says it may have changed
 * ([markStale]): the next filter then reads it again.
 *
 * A list's pull to refresh reads again what it uses: it names its refresh ([Refresh]), and what was kept before that
 * refresh is read again once for it, then kept. What is asked without a refresh takes what is kept. A read that
 * fails is thrown to whoever asked, and nothing is kept of it. Asked by several at once, a list is read once. The
 * libraries and each library's whole list are read under locks of their own: a whole list being read keeps no one
 * waiting for the libraries, nor for another library's list.
 */
internal class AbsWholeLibrary(private val now: () -> Instant = Instant::now) {

    /** The genres' names as each library writes them. */
    val genreNames = AbsGenreNames()

    private val librariesLock = Mutex()
    private var libraries: Kept<List<AbsLibraryDto>>? = null
    private val items = ConcurrentHashMap<String, LibraryItems>()

    /** The account's book libraries, in the server's order. */
    suspend fun libraries(remote: AbsRemote, refresh: Refresh?): List<AbsLibraryDto> = librariesLock.withLock {
        libraries?.takeIf { it.isFresh() }.valueFor(refresh) ?: readAndKeep(remote, refresh)
    }

    /** The account's book libraries read now, as the selector asks them, then kept in place of those kept. */
    suspend fun readLibraries(remote: AbsRemote): List<AbsLibraryDto> = librariesLock.withLock {
        readAndKeep(remote, refresh = null)
    }

    /** The whole list of the library [libraryId]'s items, in the server's order. */
    suspend fun items(remote: AbsRemote, libraryId: String, refresh: Refresh?): List<AbsItemDto> {
        val library = items.computeIfAbsent(libraryId) { LibraryItems() }
        return library.lock.withLock {
            val generation = library.generation.get()
            library.kept?.takeIf { it.generation == generation }.valueFor(refresh)
                ?: remote.api.items(libraryId, limit = WholeList, page = 0, sort = WholeListOrder, desc = 0).results
                    .also { library.kept = Kept(it, refresh, now(), generation) }
        }
    }

    /**
     * Says that the library [libraryId]'s items may have changed, as the pull to refresh [refresh] of its list the
     * server pages says, once for it: its whole list, kept or being read, is read again the next time it is asked.
     */
    fun markStale(libraryId: String, refresh: Refresh) {
        if (refresh.stale.add(libraryId)) items[libraryId]?.generation?.incrementAndGet()
    }

    private suspend fun readAndKeep(remote: AbsRemote, refresh: Refresh?): List<AbsLibraryDto> =
        remote.bookLibraries().also { libraries = Kept(it, refresh, now()) }

    private fun Kept<*>.isFresh(): Boolean = Duration.between(readAt, now()) <= LibrariesFreshness

    /** One pull to refresh of a list, which what it reads is read again for, once. */
    class Refresh {
        /** The libraries whose whole lists it said stale. */
        internal val stale: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    /**
     * What is kept of [value]: read for the refresh [readFor] (none for a read without one), at [readAt], while the
     * library's items were at [generation].
     */
    private class Kept<T>(val value: T, val readFor: Refresh?, val readAt: Instant, val generation: Int = 0)

    /** One library's whole list, kept, its lock, and how many times it was said stale ([markStale]). */
    private class LibraryItems {
        val lock = Mutex()
        val generation = AtomicInteger()

        @Volatile
        var kept: Kept<List<AbsItemDto>>? = null
    }

    /** What is kept, unless it was kept before [refresh]. */
    private fun <T> Kept<T>?.valueFor(refresh: Refresh?): T? =
        this?.takeIf { refresh == null || it.readFor === refresh }?.value
}

/**
 * The libraries a list shows among the account's book [libraries]: [library] alone, or every book library when given
 * none, as Search's "See all" asks. A library the account no longer has is asked all the same, and the server says
 * it is not there.
 */
internal fun shownLibraries(libraries: List<AbsLibraryDto>, library: ServerLibrary?): List<AbsLibraryDto> =
    if (library == null) libraries else listOfNotNull(shownLibrary(libraries, library))

/** The account's book library [library], as [libraries] know it; null for an id that is not Audiobookshelf's. */
internal fun shownLibrary(libraries: List<AbsLibraryDto>, library: ServerLibrary): AbsLibraryDto? {
    val id = library.id.absId ?: return null
    return libraries.firstOrNull { it.id == id } ?: AbsLibraryDto(id, library.name)
}

/** How long the book libraries, once read, stand for what the server holds. */
private val LibrariesFreshness: Duration = Duration.ofSeconds(60)

/** What the server is asked for a whole list: `limit=0` is everything. */
private const val WholeList = 0

/** The order a whole list is asked in, which the device sorts again anyway. */
private const val WholeListOrder = "addedAt"
