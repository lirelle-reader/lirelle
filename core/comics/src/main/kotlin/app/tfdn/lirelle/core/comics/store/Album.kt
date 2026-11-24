package app.tfdn.lirelle.core.comics.store

import app.tfdn.lirelle.core.ids.FileId
import java.io.File
import java.io.IOException

private const val CountName = "count"

/**
 * Makes the folders of a store's albums, until the store's profile closes. A reader outlives its profile by a
 * moment, and a page fetched or drawn then would make the album's folder again, in a profile's folder that
 * is being deleted: once [close] has returned no folder is made, and what is written there fails as a page
 * that could not be kept.
 */
internal class AlbumFolders {
    private var closed = false

    /** Waits for a folder being made: none is made after this returns. */
    @Synchronized
    fun close() {
        closed = true
    }

    /** Makes [directory] if it is not there; false when it is not there after that. */
    @Synchronized
    fun make(directory: File): Boolean = !closed && (directory.mkdirs() || directory.isDirectory)
}

/**
 * The folder of one version of a comic's file: its pages, named after their index, and its page count.
 *
 * @param folders what makes the folder, which is the store's to allow.
 */
internal class Album(val fileId: FileId, val directory: File, private val folders: AlbumFolders = AlbumFolders()) {
    val name: String get() = directory.name

    /**
     * Makes the album's folder before something is written there: the system may have cleared the app's
     * cache, the album's folder included. False when it is not there, and is not to be made: nothing is written.
     */
    fun makeFolder(): Boolean = folders.make(directory)

    fun page(index: Int): File = File(directory, index.toString())

    /** The page count noted at an earlier opening, or null. */
    fun rememberedCount(): Int? = try {
        File(directory, CountName).readText().trim().toIntOrNull()
    } catch (_: IOException) {
        null
    }

    /** Kept with the pages, and gone with them: it is only worth what they are. */
    fun rememberCount(count: Int) {
        try {
            if (!makeFolder()) return
            File(directory, CountName).writeText(count.toString())
        } catch (_: IOException) {
            // Not remembered: the server is asked the next time.
        }
    }
}
