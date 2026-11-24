package app.tfdn.lirelle.core.session.profile

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Where a profile lives on the device: [files] for what must be kept, [cache] for what can be made again.
 * The only way a profile object learns a path; the stores keep their names inside.
 */
data class ProfileFolders(val files: File, val cache: File) {

    /** The file of the preferences DataStore [name]. */
    fun dataStore(name: String): File = File(files, "datastore/$name.preferences_pb")

    /**
     * A preferences DataStore that lets go of its file when [life] ends, so that the folder can be deleted and
     * the same profile opened again in the same process. A file that cannot be parsed is replaced.
     */
    fun newDataStore(name: String, life: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            // Ends with the profile, but a failed write does not end the profile.
            scope = CoroutineScope(life.coroutineContext + Dispatchers.IO + SupervisorJob(life.coroutineContext[Job])),
        ) { dataStore(name) }.endingAsClosed()
}

/**
 * The same store, whose data ends its reader as a cancellation once the store has closed with its profile. Read
 * on its way out, a DataStore's data may end without a value, and `first()` would then fail with a
 * NoSuchElementException where nothing catches it, in a job that the closing profile was ending anyway.
 */
internal fun DataStore<Preferences>.endingAsClosed(): DataStore<Preferences> {
    val store = this
    return object : DataStore<Preferences> by store {
        override val data: Flow<Preferences> = store.data.onCompletion { failure ->
            // Its data never ends while the store is open: a reader that stops (first()) ends it with a failure.
            if (failure == null) throw CancellationException("The store has closed with its profile")
        }
    }
}

/** The folders of every profile, under the application's files and cache. */
class ProfileRoots(files: File, cache: File) {

    private val roots = listOf(File(files, Folder), File(cache, Folder))

    /** The folders of the profile [id]; they may not exist yet. */
    fun of(id: String) = ProfileFolders(File(roots[0], id), File(roots[1], id))

    /** The ids that have a folder, in either root: a profile's, or what an interruption left of one. */
    fun present(): Set<String> = roots.flatMapTo(mutableSetOf()) { it.list().orEmpty().asList() }

    /**
     * A place in the cache, beside the profiles' folders, for what must be out of the way at once and may be
     * deleted in its own time: a folder renamed there is nobody's, and goes in the background with the
     * folders that no profile lists, at this start or at the first one that can read the registry.
     *
     * @param name a name no profile's id can be: ids are eight hexadecimal characters.
     */
    fun aside(name: String): File = File(roots[1], name)

    /**
     * The same under the application's files, for what was kept there: setting aside is a rename, which must
     * stay on its volume, and the files and the cache may not be on the same one.
     */
    fun asideOfFiles(name: String): File = File(roots[0], name)

    /**
     * Deletes both folders of [id], as much of them as can be.
     *
     * @return false when something of them is left: the profile is then not done being removed.
     */
    fun delete(id: String): Boolean = roots.map { File(it, id).deleteRecursively() }.all { it }

    private companion object {
        const val Folder = "profiles"
    }
}
