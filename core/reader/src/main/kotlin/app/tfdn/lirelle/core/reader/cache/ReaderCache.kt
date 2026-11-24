package app.tfdn.lirelle.core.reader.cache

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.network.ApiError
import java.io.File
import kotlinx.coroutines.flow.Flow

/** One file of a book in one version: a file replaced on the server is another entry. */
data class CachedBookKey(val fileId: FileId, val contentVersion: String?)

sealed interface CacheState {
    data class Downloading(val progress: DownloadProgress) : CacheState
    /** @property kept whether [file] is the copy the downloads keep of the book, and not the cache's own. */
    data class Ready(val file: File, val kept: Boolean = false) : CacheState
    data class Failed(val error: ApiError) : CacheState
}

/** The books kept on the device to be read. */
interface ReaderCache {
    /**
     * Emits progress, then Ready or Failed. A book already there emits Ready at once.
     * Cancelling the collection cancels the download.
     *
     * The book is kept from eviction while the flow is collected, and no longer: nothing is left
     * held when the collection ends, however it ends. To keep a book that came out Ready, call
     * [hold] on receiving it, before anything that suspends.
     *
     * The copy the device keeps of a downloaded book comes first, whatever its version: it is Ready at once,
     * as the file it is, with no request and no copy. [hold] and [release] of its key then say that
     * [bookId] is in use, and [discard] leaves it alone.
     */
    fun open(bookId: BookId, key: CachedBookKey, audioless: Boolean): Flow<CacheState>

    /** The book is being read: it is never evicted until [release] is called as many times as this was. */
    fun hold(key: CachedBookKey)

    /** Gives back one [hold]. */
    fun release(key: CachedBookKey)

    /**
     * Removes this book, held or not: its file turned out not to be one the reader can open, and
     * the next [open] has to fetch it afresh. What is held stays held, and is still to be released.
     */
    suspend fun discard(key: CachedBookKey)

    /** The whole file the cache has under [key], for a download to copy; null when it has none. */
    fun cached(key: CachedBookKey): File?
}
