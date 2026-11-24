package app.tfdn.lirelle.core.comics.store

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.fileName
import java.io.File
import java.io.IOException

private const val OpenedName = "opened"
private const val PartSuffix = ".part"
private const val NoVersion = "none"

/**
 * The pages seen by one profile, in [directory]: a folder per version of a file, `<file name of its id>-<version>`.
 * It is in the profile's cache: the system may empty it, it does not make an album readable offline, and it goes
 * with the profile. Once the profile closed ([close]) nothing more is kept there.
 *
 * Holding an album and giving it back touch no file and wait for none: the main thread does both. The work
 * on the disk is done one piece at a time, apart from them.
 *
 * @param limitBytes what the albums may take together; an album a reader has open stays even above it.
 * @param now the time in milliseconds, kept on each album as the moment it was last opened.
 */
internal class ComicStore(
    private val directory: File,
    private val limitBytes: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** How many readers have each album open, by folder name. Read and changed under its own lock only. */
    private val holds = mutableMapOf<String, Int>()

    /** Held by whoever works on the disk; never by [hold] or [release]. */
    private val disk = Any()

    /** Makes the albums' folders, for as long as the profile is open. */
    private val albumFolders = AlbumFolders()

    /** Holds the album of [key] for a reader, without touching the device: [prepare] does that. */
    fun hold(key: ComicKey): Album {
        val album = Album(key.fileId, File(directory, folderName(key.fileId, key.contentVersion)), albumFolders)
        synchronized(holds) { holds[album.name] = (holds[album.name] ?: 0) + 1 }
        return album
    }

    fun release(album: Album): Unit = synchronized(holds) {
        val left = (holds[album.name] ?: return) - 1
        if (left > 0) holds[album.name] = left else holds.remove(album.name)
    }

    /**
     * The page [index] of that version of a file when it is among the pages seen, for who copies it elsewhere;
     * null otherwise. A file in an album's place is always a whole page. The album is neither held nor made
     * the last one opened, and no lock is waited for: the page may go while it is read, which its reader
     * takes as a page that was not there.
     */
    fun cached(fileId: FileId, contentVersion: String?, index: Int): File? =
        Album(fileId, File(directory, folderName(fileId, contentVersion))).page(index).takeIf { it.isFile }

    /**
     * Makes the folder of an [album] just held: the other versions of its file go, it becomes the last one
     * opened, and the others make room. Nothing is made once the profile closed.
     */
    fun prepare(album: Album): Unit = synchronized(disk) {
        folders().filter { it.name != album.name && fileOf(it.name) == album.fileId.fileName }.forEach(::drop)
        if (!album.makeFolder()) return
        // What a fetch cut by the end of the process left, unless another reader of the album is fetching.
        if (readers(album.name) == 1) {
            album.directory.listFiles().orEmpty().filter { it.name.endsWith(PartSuffix) }.forEach { it.delete() }
        }
        try {
            File(album.directory, OpenedName).apply { createNewFile() }.setLastModified(now())
        } catch (_: IOException) {
            // An album without the mark is the first to go.
        }
        trim()
    }

    /** Brings the store back under its limit. */
    fun trim(): Unit = synchronized(disk) {
        val albums = folders().map { folder ->
            val bytes = folder.listFiles().orEmpty().sumOf { it.length() }
            KeptAlbum(folder.name, bytes, File(folder, OpenedName).lastModified(), held = readers(folder.name) > 0)
        }
        albumsToDrop(albums, limitBytes).forEach { drop(File(directory, it)) }
    }

    /**
     * The profile closes: a page that comes from now on is not kept. The profile's folder is about to be
     * deleted when it was removed, and a late page would make it again, with a picture of a book in it. The
     * pages go with that folder: nothing is deleted here.
     */
    fun close() = albumFolders.close()

    /**
     * The album in [folder] goes, unless a reader has it open: it may have been opened since it was listed.
     * One opened while it goes is made again by its [prepare], which waits for this to end.
     */
    private fun drop(folder: File) {
        if (readers(folder.name) == 0) folder.deleteRecursively()
    }

    private fun readers(name: String): Int = synchronized(holds) { holds[name] ?: 0 }

    private fun folders(): List<File> = directory.listFiles().orEmpty().filter { it.isDirectory }
}

/** The id, then the version with only its letters and digits: a name any file system takes. */
private fun folderName(fileId: FileId, contentVersion: String?): String {
    val version = contentVersion?.map { if (it.isLetterOrDigit()) it else '_' }?.joinToString("") ?: NoVersion
    return "${fileId.fileName}-$version"
}

/**
 * The file name of the id an album's folder [name] was made of: what comes before its last `-`, the version
 * having none. The id's own name may hold a `-`: a folder is never matched by its start, which another id's may
 * share.
 */
private fun fileOf(name: String): String = name.substringBeforeLast('-')
