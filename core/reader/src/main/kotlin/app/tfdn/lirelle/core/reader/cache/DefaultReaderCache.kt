package app.tfdn.lirelle.core.reader.cache

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.DownloadProgress
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only a name: the cache keeps a whole file by its key, whatever its format, and nobody reads the extension. */
private const val BookSuffix = ".epub"
private const val PartSuffix = ".part"
private const val NoVersion = "none"

/**
 * Books as files of [directory], named after their file id and version. A download is written
 * next to its final place under a temporary name and renamed when complete, so a file with a
 * book's name is always a whole book.
 *
 * A book is downloaded by one reader at a time, and different books at the same time: a download
 * that stalls keeps nobody from another book, and a book already there never waits for a download.
 *
 * A reader outlives its profile by a moment: once [close] has returned the cache gives the books it has, makes
 * no directory and keeps no new book, so that nothing of an account that left is written again into a folder
 * that is being deleted.
 *
 * @param limitBytes what the books may take together; the book being opened is kept even alone above it.
 * @param io where files are read and written.
 * @param now the time in milliseconds, kept on each file as the moment it was last opened.
 */
class DefaultReaderCache(
    private val repository: ReaderRepository,
    private val directory: File,
    private val limitBytes: Long,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    downloaded: DownloadedFiles = DownloadedFiles.None,
    books: BookHolds = BookHolds.None,
) : ReaderCache {

    /** Held for the time of a look at the directory or a change to it, never for a download. */
    private val shelf = Mutex()

    /** One download per book: two readers of one book never write the same file. */
    private val downloads = KeyedLocks()
    private val holds = Holds()

    /** The keys the downloads answer for: their holds are the book's, not the cache's. */
    private val kept = KeptBooks(downloaded, books, io)

    /** Looked at and changed under [shelf] only. */
    private val folder = CacheFolder(directory)

    /**
     * The cache's profile closes. Waits for whoever looks at the directory or changes it: nothing is made or
     * moved into place there after this returns. A download in flight goes on, and is thrown away at its end.
     */
    suspend fun close() = shelf.withLock { folder.close() }

    /**
     * The flow holds the book for itself, from before the download until the collector has taken
     * the last state, and gives that back whatever happens: a collector cancelled as the book
     * comes out ready leaves nothing held. The hold that outlives the flow is the collector's own.
     */
    override fun open(bookId: BookId, key: CachedBookKey, audioless: Boolean): Flow<CacheState> = flow {
        // The downloads' own file first, whatever its version: no request, no copy.
        if (kept.offer(bookId, key) { emit(CacheState.Ready(it, kept = true)) }) return@flow
        val name = fileName(key)
        holds.add(name)
        try {
            emitAll(
                channelFlow {
                    val progress: (DownloadProgress) -> Unit = { trySend(CacheState.Downloading(it)) }
                    send(withContext(io) { obtain(key, audioless, progress) })
                },
            )
        } finally {
            holds.remove(name)
        }
    }

    override fun hold(key: CachedBookKey) {
        if (!kept.hold(key)) holds.add(fileName(key))
    }

    override fun release(key: CachedBookKey) {
        if (!kept.release(key)) holds.remove(fileName(key))
    }

    /** The file refused was the downloads' own when the key is theirs: the cache's copy is not the one to blame. */
    override suspend fun discard(key: CachedBookKey) {
        if (kept.answersFor(key)) return
        withContext(io) { shelf.withLock { File(directory, fileName(key)).delete() } }
    }

    override fun cached(key: CachedBookKey): File? = File(directory, fileName(key)).takeIf { it.isFile }

    private suspend fun obtain(
        key: CachedBookKey,
        audioless: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): CacheState {
        val book = File(directory, fileName(key))
        if (shelf.withLock { folder.inPlace(book) }) return shelved(key, book, new = false)
        return downloads.withLock(book.name) {
            // Another reader of the same book may have fetched it while this one waited.
            val there = shelf.withLock { folder.inPlace(book) }
            val failure = if (there) null else download(key, audioless, book, onProgress)
            if (failure != null) CacheState.Failed(failure) else shelved(key, book, new = !there)
        }
    }

    /**
     * The book is in place: it is the last one opened, and the others make room for it, the
     * versions it replaces first.
     */
    private suspend fun shelved(key: CachedBookKey, book: File, new: Boolean): CacheState = shelf.withLock {
        if (new) {
            books(directory).filter { it != book && fileOf(it.name) == key.fileId.fileName && !holds.has(it.name) }
                .forEach { it.delete() }
        }
        book.setLastModified(now())
        evict(directory, limitBytes, holds, keep = book)
        CacheState.Ready(book)
    }

    /**
     * Null when [book] is in place; nothing is left behind otherwise, cancellation included. For a cache that
     * closed nothing is asked of the server, and what was being fetched as it closed is not put in place.
     */
    private suspend fun download(
        key: CachedBookKey,
        audioless: Boolean,
        book: File,
        onProgress: (DownloadProgress) -> Unit,
    ): ApiError? {
        val part = File(directory, book.name + PartSuffix)
        var complete = false
        try {
            val file = shelf.withLock { folder.newFile(part) } ?: return ApiError.Unexpected
            val result = file.use { repository.download(key.fileId, audioless, it, onProgress) }
            if (result is ApiResult.Failure) return result.error
            shelf.withLock { folder.put(part, book) }
            complete = true
            return null
        } catch (_: IOException) {
            // The device's side: no room, a cache directory cleared under us, or a profile that closed.
            return ApiError.Unexpected
        } finally {
            if (!complete) part.delete()
        }
    }
}

/**
 * What makes the cache's [directory] and puts files there, until the cache closes. Not safe by itself: the
 * cache calls it holding its lock, so that closing waits for a file being made.
 */
private class CacheFolder(private val directory: File) {
    private var closed = false
    private var cleaned = false

    fun close() {
        closed = true
    }

    /**
     * Whether [book] is there, in a directory that exists: it is made each time, since the system
     * may clear the app's cache, directory included, while the app runs. Once per cache, so once each
     * time its profile opens, and before the first download starts, what an interrupted download left is
     * removed. Closed, it only looks: a directory that is gone was deleted with the profile.
     */
    fun inPlace(book: File): Boolean {
        if (!closed) {
            directory.mkdirs()
            if (!cleaned) {
                directory.listFiles().orEmpty().filter { it.name.endsWith(PartSuffix) }.forEach { it.delete() }
                cleaned = true
            }
        }
        return book.isFile
    }

    /** The file a download is written to; null once closed: none is made. */
    fun newFile(part: File): OutputStream? = if (closed) null else part.outputStream()

    /** The whole download takes its book's name; refused once closed. */
    fun put(part: File, book: File) {
        if (closed) throw IOException("The cache is closed: the book is not kept")
        Files.move(part.toPath(), book.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Removes the least recently opened books of [directory] over [limitBytes], never [keep] nor a book held. */
private fun evict(directory: File, limitBytes: Long, holds: Holds, keep: File) {
    val books = books(directory)
    var excess = books.sumOf { it.length() } - limitBytes
    val removable = books.filter { it != keep && !holds.has(it.name) }.sortedBy { it.lastModified() }
    for (book in removable) {
        if (excess <= 0) break
        val size = book.length()
        if (book.delete()) excess -= size
    }
}

/** The whole books of a directory: not what is still being downloaded. */
private fun books(directory: File): List<File> = directory.listFiles().orEmpty().filter { it.name.endsWith(BookSuffix) }

/** The id, then the version with only its letters and digits: a name any file system takes. */
private fun fileName(key: CachedBookKey): String {
    val version = key.contentVersion?.map { if (it.isLetterOrDigit()) it else '_' }?.joinToString("") ?: NoVersion
    return "${key.fileId.fileName}-$version$BookSuffix"
}

/**
 * The file name of the id a book's [name] was made of: what comes before its last `-`, the version having none.
 * The id's own name may hold a `-`: a name is never matched by its start, which another id's may share.
 */
private fun fileOf(name: String): String = name.substringBeforeLast('-')

/** How many readers have each book open, by file name. */
private class Holds {
    private val counts = mutableMapOf<String, Int>()

    @Synchronized
    fun add(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    @Synchronized
    fun remove(name: String) {
        val left = (counts[name] ?: return) - 1
        if (left > 0) counts[name] = left else counts.remove(name)
    }

    @Synchronized
    fun has(name: String): Boolean = name in counts
}

/** A lock per name, kept only while somebody holds it or waits for it. */
private class KeyedLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(name: String, action: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(name, ::Entry).also { it.users++ } }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(name) }
        }
    }
}
