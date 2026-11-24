package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileDataFormats
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the folders of a profile are renamed to when they are set aside, followed by its id: a name no profile's
 * id can have (ids are eight hexadecimal characters), so that what is set aside is nobody's, and the start's
 * sweep deletes whatever of it is still there.
 */
internal const val OldDataPrefix = "old-data-"

/**
 * Empties the folders of a profile whose data is of an older format ([Profile.dataFormat]) before its objects
 * are built: its stores are not migrated, they start again. Nothing of the profile is open meanwhile — it is
 * about to be opened, at the start or at a switch.
 *
 * Emptying is renaming the two folders aside ([OldDataPrefix]), which is quick whatever they hold, then writing
 * the current format; what was set aside is deleted in the background. In that order, so that an interruption
 * between the two finds folders that are already empty at the next start, sets nothing more aside, and writes
 * the format then.
 *
 * Only what is the profile's goes: the account's picture, which the device keeps beside the folders, and the
 * device's settings, stay.
 */
internal class ProfileDataReset(
    private val roots: ProfileRoots,
    private val formats: ProfileDataFormats,
    private val making: GraphMaking,
) {
    /** Where what was set aside is deleted: it may hold gigabytes of downloads, and is waited for by nobody. */
    private val deleting = CoroutineScope(SupervisorJob() + making.io)

    /**
     * Whether [profile] may be opened: of the current format already, or made so here. When its folders could
     * not be set aside, or its format not written, it is withheld from this start ([ProfileDataFormats.withhold])
     * and keeps its format: the next start tries again. That is told to the log by its kind alone, with the step
     * that failed. What was set aside before the format could not be written is deleted all the same: it is
     * nobody's, and the next start finds the folders empty already.
     */
    suspend fun readied(profile: Profile): Boolean {
        if (profile.dataFormat >= CurrentDataFormat) return true
        val aside = try {
            setAside(profile.id)
        } catch (failure: IOException) {
            return withheld(profile, "The data of a profile of an older format could not be set aside", failure)
        }
        if (aside.isNotEmpty()) deleting.launch { delete(aside) }
        try {
            formats.dataStartedAgain(profile.id)
        } catch (failure: IOException) {
            return withheld(profile, "The data was set aside, its new format could not be written", failure)
        }
        return true
    }

    private suspend fun withheld(profile: Profile, step: String, failure: IOException): Boolean {
        making.warn("$step: not opened", failure)
        formats.withhold(profile.id)
        return false
    }

    /**
     * Renames both folders of [id] aside, each beside the profiles' folders of its own root: a rename must stay
     * on its volume. A folder that is not there is empty already.
     *
     * @return where they went.
     * @throws IOException when one could not be renamed. One already renamed stays aside: it is nobody's.
     */
    private fun setAside(id: String): List<File> {
        val folders = roots.of(id)
        return listOfNotNull(
            setAside(folders.files, "$OldDataPrefix$id", roots::asideOfFiles),
            setAside(folders.cache, "$OldDataPrefix$id", roots::aside),
        )
    }

    private fun delete(aside: List<File>) {
        if (!aside.map { it.deleteRecursively() }.all { it }) {
            making.warn("Data set aside could not all be deleted: left for the start's sweep", null)
        }
    }
}

/**
 * Renames [folder] to [name] beside it, or `name-2`, `name-3`… when that is taken: a start that was interrupted
 * may have left one that no sweep has deleted yet.
 *
 * @return where it went; null when it was not there.
 * @throws IOException when it could not be renamed. The message names no path.
 */
private fun setAside(folder: File, name: String, aside: (String) -> File): File? {
    if (!folder.exists()) return null
    val free = generateSequence(1) { it + 1 }
        .map { aside(if (it == 1) name else "$name-$it") }
        .first { !it.exists() }
    if (!folder.renameTo(free)) throw IOException("A folder of a profile could not be set aside")
    return free
}
