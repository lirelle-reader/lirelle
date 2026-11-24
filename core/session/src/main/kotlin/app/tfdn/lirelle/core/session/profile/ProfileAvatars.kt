package app.tfdn.lirelle.core.session.profile

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The pictures of the profiles' accounts, kept on the device. Device data, not a profile's: the avatar of a
 * profile that sleeps is shown in the list of profiles while none of its objects exist, so its picture cannot
 * live in what only those objects reach. The open profile fetches its own picture and hands it over here.
 */
interface ProfileAvatars {
    /**
     * The picture kept for [profileId], or null; follows what is kept. A picture that replaced another is
     * told as a file that does not equal the one before ([KeptPicture]), although its path is the same: who
     * shows it loads it again.
     */
    fun picture(profileId: String): Flow<File?>

    /** The `avatarUrl` the kept picture was fetched for; null when none is kept. */
    suspend fun keptFor(profileId: String): String?

    /**
     * Keeps [bytes] as the picture of [profileId], fetched for [avatarUrl]. The picture itself is written
     * whole or not at all; what it was fetched for is written after it.
     *
     * @throws IOException when something could not be written. Either the picture kept before is still there,
     *   untouched, and nothing is told; or the new picture is in place and told to who follows [picture],
     *   while [keptFor] does not say [avatarUrl] — it says the one of the picture before, or nothing —, so
     *   that the caller fetches it again.
     */
    suspend fun keep(profileId: String, avatarUrl: String, bytes: ByteArray)

    suspend fun delete(profileId: String)

    /**
     * Deletes the pictures of every id not in [listed], and what a write that was cut short left behind
     * (the `.tmp` files), whoever it was for.
     */
    suspend fun sweep(listed: Set<String>)
}

/**
 * A kept picture as [ProfileAvatars.picture] tells it: the file, and which of the pictures written there in
 * this process it is. The path of a profile's picture never changes, and two plain files of one path are
 * equal: a state or a flow would not tell the second, and nothing would show the new picture. Two of these
 * are equal only when no picture was written between them.
 *
 * @property version changes each time a picture is written for the profile; means nothing across processes.
 */
class KeptPicture internal constructor(file: File, val version: Long) : File(file.path) {

    override fun equals(other: Any?): Boolean = other is KeptPicture && version == other.version && path == other.path

    override fun hashCode(): Int = path.hashCode() * HashStep + version.hashCode()

    private companion object {
        const val HashStep = 31
    }
}

/**
 * [ProfileAvatars] in [folder], which is the device's and no profile's: one file named by the profile's id,
 * and beside it `<id>.for`, which holds the `avatarUrl` the picture was fetched for.
 *
 * A picture is written beside its place and renamed into it, the picture before its `.for`: an interruption,
 * or a `.for` that cannot be put in its place, leaves the old picture, or the new one still said to be the
 * old one's — which is then fetched again — never a picture cut short, nor an old picture said to be the new
 * one. Writes run one at a time, on [io].
 */
class FileProfileAvatars(private val folder: File, private val io: CoroutineDispatcher = Dispatchers.IO) :
    ProfileAvatars {

    /** One at a time: a sweep deletes what a write cut short left, and must not take a write under way for it. */
    private val writing = Mutex()

    /** Counts the writes of this process; each change wakes who follows a picture. */
    private val changes = MutableStateFlow(0L)

    /** For each profile written to in this process, the count at its last picture. */
    private val versions = ConcurrentHashMap<String, Long>()

    override fun picture(profileId: String): Flow<File?> =
        changes.map { kept(profileId) }.distinctUntilChanged().flowOn(io)

    private fun kept(profileId: String): KeptPicture? =
        pictureOf(profileId).takeIf { it.isFile }?.let { KeptPicture(it, versions[profileId] ?: 0L) }

    override suspend fun keptFor(profileId: String): String? = withContext(io) {
        val told = forOf(profileId)
        if (pictureOf(profileId).isFile && told.isFile) runCatching { told.readText() }.getOrNull() else null
    }

    override suspend fun keep(profileId: String, avatarUrl: String, bytes: ByteArray) = write {
        folder.mkdirs()
        val picture = File(folder, profileId + Unfinished)
        val told = File(folder, profileId + ForSuffix + Unfinished)
        try {
            picture.writeBytes(bytes)
            told.writeText(avatarUrl)
            picture.moveTo(pictureOf(profileId))
            // As soon as the picture is there, whatever becomes of its `.for`: who shows it loads the new one.
            versions[profileId] = changes.value + 1
            told.moveTo(forOf(profileId))
        } finally {
            picture.delete()
            told.delete()
        }
    }

    override suspend fun delete(profileId: String) = write {
        // Its `.for` first: a picture left without it is not said to be anybody's, and is fetched again.
        forOf(profileId).delete()
        pictureOf(profileId).delete()
        versions -= profileId
    }

    override suspend fun sweep(listed: Set<String>) = write {
        folder.listFiles().orEmpty()
            .filter { it.name.endsWith(Unfinished) || it.name.substringBefore('.') !in listed }
            .forEach { it.delete() }
        versions.keys.retainAll(listed)
    }

    /** Runs [change] alone and off the caller's thread, then tells that the pictures changed. */
    private suspend fun write(change: () -> Unit) = withContext(io) {
        writing.withLock {
            try {
                change()
            } finally {
                changes.value += 1
            }
        }
    }

    private fun pictureOf(profileId: String) = File(folder, profileId)

    private fun forOf(profileId: String) = File(folder, profileId + ForSuffix)

    private fun File.moveTo(place: File) {
        if (!renameTo(place)) throw IOException("A profile's picture could not be put in its place")
    }

    private companion object {
        const val ForSuffix = ".for"

        /** Ends the name of what is being written; no profile's id holds a dot. */
        const val Unfinished = ".tmp"
    }
}
