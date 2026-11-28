package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibraryDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the profile keeps of its server for the whole of its session: its book libraries (for their order and the
 * shape of their covers) and, once a list is narrowed on the device, each library's whole list of items
 * (`limit=0`), so that another filter on the same library asks nothing. A big library makes the first filter slower
 * (721 items are 1.4 MB, 0.4 s on the test server); that is accepted.
 *
 * A list's pull to refresh reads again what it uses: it names its refresh ([Refresh]), and what was kept before that
 * refresh is read again once for it, then kept. What is asked without a refresh takes what is kept. A read that
 * fails is thrown to whoever asked, and nothing is kept of it. Asked by several at once, a list is read once.
 */
internal class AbsWholeLibrary {

    private val lock = Mutex()
    private var libraries: Kept<List<AbsLibraryDto>>? = null
    private val items = HashMap<String, Kept<List<AbsItemDto>>>()

    /** The account's book libraries, in the server's order. */
    suspend fun libraries(remote: AbsRemote, refresh: Refresh?): List<AbsLibraryDto> = lock.withLock {
        libraries.valueFor(refresh) ?: remote.bookLibraries().also { libraries = Kept(it, refresh) }
    }

    /** The whole list of the library [libraryId]'s items, in the server's order. */
    suspend fun items(remote: AbsRemote, libraryId: String, refresh: Refresh?): List<AbsItemDto> = lock.withLock {
        items[libraryId].valueFor(refresh)
            ?: remote.api.items(libraryId, limit = WholeList, page = 0, sort = WholeListOrder, desc = 0).results
                .also { items[libraryId] = Kept(it, refresh) }
    }

    /** One pull to refresh of a list, which what it reads is read again for, once. */
    class Refresh

    private class Kept<T>(val value: T, val readFor: Refresh?)

    /** What is kept, unless it was kept before [refresh]. */
    private fun <T> Kept<T>?.valueFor(refresh: Refresh?): T? =
        this?.takeIf { refresh == null || it.readFor === refresh }?.value
}

/** What the server is asked for a whole list: `limit=0` is everything. */
private const val WholeList = 0

/** The order a whole list is asked in, which the device sorts again anyway. */
private const val WholeListOrder = "addedAt"
