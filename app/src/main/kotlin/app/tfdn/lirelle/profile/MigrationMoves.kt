package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.downloads.DownloadsFiles
import app.tfdn.lirelle.core.reader.ReadingFiles
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileRoots
import app.tfdn.lirelle.di.CoversFolder
import java.io.File
import java.io.IOException

/** Where an installation from before the profiles kept something of the account. */
internal enum class OldRoot { Preferences, Files, Cache }

/**
 * A file or a folder of the account that a profile keeps under the same name, in its own folders: moved as it
 * is. The names are the modules' own, which their stores use.
 */
internal data class MovedPlace(val root: OldRoot, val name: String)

/** The preferences files first: the positions and what waits to be sent are what cannot be made again. */
internal val MovedPlaces = listOf(
    MovedPlace(OldRoot.Preferences, ReadingFiles.Store),
    MovedPlace(OldRoot.Preferences, ListeningFiles.Store),
    MovedPlace(OldRoot.Preferences, DownloadsFiles.Store),
    MovedPlace(OldRoot.Files, DownloadsFiles.Folder),
    MovedPlace(OldRoot.Files, ListeningFiles.ReadAlong),
    MovedPlace(OldRoot.Cache, CoversFolder),
    MovedPlace(OldRoot.Cache, ReadingFiles.Cache),
    MovedPlace(OldRoot.Cache, ComicsFiles.Pages),
)

/** Where the old installation kept it. */
internal fun MovedPlace.old(context: Context): File = when (root) {
    OldRoot.Preferences -> context.preferencesDataStoreFile(name)
    OldRoot.Files -> File(context.filesDir, name)
    OldRoot.Cache -> File(context.cacheDir, name)
}

/** Where the profile of [folders] keeps it. */
internal fun MovedPlace.new(folders: ProfileFolders): File = when (root) {
    OldRoot.Preferences -> folders.dataStore(name)
    OldRoot.Files -> File(folders.files, name)
    OldRoot.Cache -> File(folders.cache, name)
}

/**
 * Moves [from] to [to] by renaming it: on the same volume, so nothing is copied and a download is not written
 * again; and at once, so that an interruption leaves it whole at one place or the other. A source that is not
 * there was moved by an earlier start, or never existed: there is nothing to do.
 *
 * @throws IOException when it could not be renamed. It names the two paths, which say nothing of the account,
 *   and nothing of what they hold.
 */
internal fun move(from: File, to: File) {
    if (!from.exists()) return
    to.parentFile?.mkdirs()
    if (!from.renameTo(to)) throw IOException("Could not move $from to $to")
}

/**
 * Deletes [place] and what it holds; one that is not there is deleted already.
 *
 * @throws IOException when something of it is left. It names the path, and nothing of what it holds.
 */
internal fun delete(place: File) {
    if (!place.deleteRecursively()) throw IOException("Could not delete $place")
}

/**
 * Deletes what DataStore left beside the preferences file [file] once it is moved or deleted: the scratch
 * file of a write that was cut short, and anything else it names after the file. Nothing reads them, and
 * nothing would ever delete them: no store opens a file at the old place again.
 *
 * @throws IOException when something of them is left. It names the file by its own name only.
 */
internal fun deleteLeftoversOf(file: File) {
    val leftovers = file.parentFile?.listFiles { sibling -> sibling.name.startsWith("${file.name}.") }.orEmpty()
    val left = leftovers.count { !it.deleteRecursively() }
    if (left > 0) throw IOException("$left leftovers of ${file.name} could not be deleted")
}

/**
 * The names things are set aside under, beside the profiles' folders: none can be a profile's id, which is
 * eight hexadecimal characters. The old audio cache, and what an account that is nobody's any more kept under
 * the application's files and under its cache.
 */
private const val OldAudio = "old-audio"
private const val OldFiles = "old-files"
private const val OldCache = "old-cache"

/**
 * Puts [old] out of the way, to be deleted in its own time: renamed beside the profiles' folders, where it is
 * nobody's and the sweep of the start deletes it in the background, at this start or at a later one. Under the
 * first name that is free, [name] then `name-2`…: an earlier attempt, or another place set aside before this
 * one, may have taken a name that no sweep has passed over yet. One that is not there was set aside by an
 * earlier start, or never existed.
 *
 * @param aside where a name leads, beside the profiles' folders of the same volume as [old].
 * @throws IOException when it could neither be renamed nor, the slow way, deleted.
 */
internal fun setAside(old: File, name: String, aside: (String) -> File) {
    if (!old.exists()) return
    val free = generateSequence(1) { it + 1 }
        .map { aside(if (it == 1) name else "$name-$it") }
        .first { !it.exists() }
    free.parentFile?.mkdirs()
    if (!old.renameTo(free)) delete(old)
}

/**
 * What [place] held is nobody's — nobody was signed in, or with a session that can never be read again: it
 * goes. A preferences file is deleted at once. A folder is not: the downloads may hold gigabytes, and this
 * runs on the main thread before the first screen. It is set aside ([setAside]), each root on its own volume,
 * for the start's sweep.
 */
internal fun discard(place: MovedPlace, context: Context, roots: ProfileRoots) {
    val old = place.old(context)
    when (place.root) {
        OldRoot.Preferences -> delete(old)
        OldRoot.Files -> setAside(old, OldFiles, roots::asideOfFiles)
        OldRoot.Cache -> setAside(old, OldCache, roots::aside)
    }
}

/**
 * The audio streamed by the old installation is not moved into the profile: its index is in a database of the
 * whole application, and a profile's cache has its own beside it, so the files would be nobody's in the new
 * place. It goes, and the audio is streamed again.
 *
 * It is not deleted here, though: it may hold a gigabyte in thousands of files, and this runs before the first
 * screen. It is set aside ([setAside], [ProfileRoots.aside]) for the start's sweep.
 *
 * @throws IOException when it could neither be renamed nor, the slow way, deleted.
 */
internal fun setOldAudioCacheAside(context: Context, roots: ProfileRoots) {
    setAside(File(context.cacheDir, ListeningFiles.Cache), OldAudio, roots::aside)
}

/** The index of the old audio cache, which names what was listened to: Media3's database of before. */
internal fun deleteOldAudioIndex(context: Context) {
    context.deleteDatabase(ListeningFiles.OldCacheIndex)
}
