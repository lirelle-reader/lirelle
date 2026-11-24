package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.fileName
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.network.ApiResult
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** The maps kept on the device. */
const val ReadAlongMapsKept = 10

private const val Suffix = ".map.gz"
private const val PartialSuffix = ".partial"
private const val VersionTagLength = 16
private const val NoVersion = "none"

interface ReadAlongMapStore {
    /** Kept on the device per file and version; fetched (playlist + spine) when missing. Null when it cannot be had. */
    suspend fun map(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap?

    /**
     * Writes the map into [target] as it is kept here (fetched when missing), for someone who keeps it on their
     * own: [readAlongMapFrom] reads it back. False when it cannot be had or written; [target] is then untouched.
     */
    suspend fun copyTo(bookId: BookId, fileId: FileId, contentVersion: String?, target: File): Boolean = false
}

/** The map [ReadAlongMapStore.copyTo] wrote into [file]; null when it does not read, or is another file's. */
fun readAlongMapFrom(file: File, bookId: BookId, fileId: FileId): ReadAlongMap? =
    storedMap(file, bookId, fileId) { true }

private fun storedMap(file: File, bookId: BookId, fileId: FileId, version: (String?) -> Boolean): ReadAlongMap? = try {
    val stored = file.inputStream().use(::readStoredReadAlong)
    stored.playlist()?.takeIf { stored.fileId == fileId && version(stored.contentVersion) }
        ?.let { ReadAlongMap(bookId, fileId, stored.contentVersion, it, stored.spineEntries()) }
} catch (_: IOException) {
    null
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: IndexOutOfBoundsException) {
    null
}

/**
 * One file per read-along file in [directory], for its latest version: another version replaces it. At most
 * [kept] files, the least recently used out ([now] stamps each use). A file that cannot be read is missing. The
 * last map used is also kept in memory. A map holds the text of a book another account may not be allowed:
 * [directory] is in the folder of the profile, and goes with it; once the profile closed ([close]) nothing more
 * is written there. A map of thousands of sentences is made on [compute], not on the caller's thread, which may
 * be the main one.
 */
class FileReadAlongMapStore(
    private val directory: File,
    private val repository: ReadAlongRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val kept: Int = ReadAlongMapsKept,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ReadAlongMapStore {

    private val mutex = Mutex()
    private var recent: ReadAlongMap? = null

    @Volatile
    private var closed = false

    /**
     * The profile closes: a map fetched from now on is given, not kept. Its folder is about to be deleted, and
     * a late write would make it again, with the text of a book in it.
     */
    fun close() {
        closed = true
    }

    override suspend fun map(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? = mutex.withLock {
        val inMemory = recent?.takeIf {
            it.bookId == bookId && it.fileId == fileId && it.contentVersion == contentVersion
        }
        val known = inMemory ?: withContext(io) { read(bookId, fileId, contentVersion) }
        if (known != null) withContext(io) { fileOf(fileId, contentVersion).setLastModified(now()) }
        (known ?: fetch(bookId, fileId, contentVersion))?.also { recent = it }
    }

    override suspend fun copyTo(bookId: BookId, fileId: FileId, contentVersion: String?, target: File): Boolean =
        mutex.withLock {
            val source = fileOf(fileId, contentVersion)
            if (!withContext(io) { source.isFile }) fetch(bookId, fileId, contentVersion)
            withContext(io) { source.isFile && copy(source, target) }
        }

    private fun fileOf(fileId: FileId, contentVersion: String?) =
        File(directory, "${fileId.fileName}-${versionTag(contentVersion)}$Suffix")

    private fun read(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? {
        val file = fileOf(fileId, contentVersion).takeIf { it.isFile } ?: return null
        val map = storedMap(file, bookId, fileId) { it == contentVersion }
        if (map == null) file.delete()
        return map
    }

    private suspend fun fetch(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? {
        val playlist = (repository.playlist(bookId, fileId) as? ApiResult.Success)?.value ?: return null
        val spine = (repository.spine(bookId, fileId) as? ApiResult.Success)?.value ?: return null
        withContext(io) { write(fileId, contentVersion, storedReadAlong(fileId, contentVersion, playlist, spine)) }
        return withContext(compute) { ReadAlongMap(bookId, fileId, contentVersion, playlist, spine) }
    }

    /**
     * Whole or not at all: written beside [target], then moved onto it. Not once the profile closed: the
     * target is in the profile's folder too, and its folder would be made again.
     */
    private fun copy(source: File, target: File): Boolean {
        if (closed) return false
        val partial = File(target.parentFile, "${target.name}$PartialSuffix")
        val copied = try {
            target.parentFile?.mkdirs()
            source.copyTo(partial, overwrite = true)
            partial.renameTo(target)
        } catch (_: IOException) {
            false
        }
        if (!copied) partial.delete()
        return copied
    }

    /** Kept if it can be; a map that cannot be written is only not kept. */
    private fun write(fileId: FileId, contentVersion: String?, stored: StoredReadAlong) {
        if (closed) return
        val target = fileOf(fileId, contentVersion)
        val partial = File(directory, "${target.name}$PartialSuffix")
        val written = try {
            directory.mkdirs()
            partial.outputStream().use(stored::writeTo)
            partial.renameTo(target)
        } catch (_: IOException) {
            false
        }
        if (!written) {
            partial.delete()
            return
        }
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(Suffix) && idNameOf(it.name) == fileId.fileName && it.name != target.name }
            .forEach { it.delete() }
        target.setLastModified(now())
        evict()
    }

    /** Also drops what an interrupted write left. */
    private fun evict() {
        directory.listFiles().orEmpty().filter { it.name.endsWith(PartialSuffix) }.forEach { it.delete() }
        directory.listFiles().orEmpty().filter { it.name.endsWith(Suffix) }
            .sortedByDescending { it.lastModified() }
            .drop(kept)
            .forEach { it.delete() }
    }
}

/**
 * The file name of the id a kept map's [name] was made of: what comes before the last `-` of its name without the
 * suffix, the version tag having none. The id's own name may hold a `-`: a map is never matched by the start of
 * its name, which another id's may share.
 */
private fun idNameOf(name: String): String = name.removeSuffix(Suffix).substringBeforeLast('-')

/** A short name for a content version, safe in a file name. */
private fun versionTag(contentVersion: String?): String = contentVersion?.let { version ->
    MessageDigest.getInstance("SHA-256").digest(version.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(VersionTagLength)
} ?: NoVersion
