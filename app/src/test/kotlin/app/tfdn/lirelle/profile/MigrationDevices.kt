package app.tfdn.lirelle.profile

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.session.DataStoreProfileRegistry
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.core.session.profile.ProfileRoots
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job

/**
 * A device of its own inside the test's: its files and its cache under [root], so that a test has several,
 * each migrated from the same old installation, and none shares a file with the application the test runs in,
 * whose registry is open. The shared preferences and the databases are the test's own: [reset] empties them.
 */
internal class OldDevice(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

    /** Where the preferences files are looked for. */
    override fun getApplicationContext(): Context = this

    fun reset() {
        deleteSharedPreferences("player_last_book")
        deleteDatabase("exoplayer_internal.db")
    }
}

/** What stops a migration where a test wants it: an interruption, not a failure of the migration's own. */
internal class Interrupted(at: String) : RuntimeException("Interrupted after: $at")

/**
 * One start of the application on [device]: the registry read from its file again, and the migration made of
 * it, by hand. The profiles are p0, p1… at each start: the first one made is always p0.
 *
 * @param reached told of each part of the migration as it is done.
 */
internal class MigrationStart(device: Context, cipher: TokenCipher, reached: (String) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryFile = device.preferencesDataStoreFile("profiles")
    private var ids = 0

    val registry = DataStoreProfileRegistry(
        PreferenceDataStoreFactory.create(scope = scope) { registryFile },
        cipher,
    ) { "p${ids++}" }

    val migration = ProfileMigration(
        device,
        registry.adoption(device.preferencesDataStoreFile("session"), registryFile),
        ProfileRoots(device.filesDir, device.cacheDir),
        reached,
    )

    /** The process ends: the registry's file is let go of. */
    suspend fun end() = scope.coroutineContext.job.cancelAndJoin()
}

/**
 * A device under a folder of its own, with the old installation that [install] writes on it.
 *
 * @param cipher what encrypted the tokens of the old session.
 */
internal suspend fun oldDevice(
    base: Context,
    root: File,
    cipher: TokenCipher,
    install: suspend OldInstallation.() -> Unit = { write() },
): OldDevice {
    val device = OldDevice(base, root)
    device.reset()
    OldInstallation(device, cipher).install()
    return device
}

/**
 * One start of the application on this device: the migration runs, and the process ends.
 *
 * @param interruptAfter the part after which the process dies; null when it lives to the end.
 * @param at told of each part as it is done, before the process dies there.
 * @return the parts done, in order.
 */
internal suspend fun OldDevice.start(
    cipher: TokenCipher,
    interruptAfter: String? = null,
    at: (String) -> Unit = {},
): List<String> {
    val parts = mutableListOf<String>()
    val start = MigrationStart(this, cipher) { part ->
        parts += part
        at(part)
        if (part == interruptAfter) throw Interrupted(part)
    }
    try {
        start.migration.run()
    } catch (_: Interrupted) {
        // The process died there.
    } finally {
        start.end()
    }
    return parts
}

/** What the registry says at a start. */
internal data class RegistryFacts(
    val state: SessionState,
    val current: String?,
    val listed: Set<String>?,
    val moving: List<String>,
    val removing: List<String>,
)

/**
 * Everything a device holds, to compare two of them.
 *
 * @property files every file and folder under its root, by its path there, with what it holds: the keys and
 *   values of a preferences file, the text of any other. The registry's own file is told by [registry].
 */
internal data class DeviceSnapshot(
    val files: Map<String, String>,
    val registry: RegistryFacts,
    val lastBook: Map<String, Any?>,
    val databases: List<String>,
)

/**
 * Whether this path of a [DeviceSnapshot] is where something of an account still is that should not be: in
 * the old place of the downloads, or in a profile's folder. What was set aside for the start's sweep — under
 * `profiles/old-…`, a name no profile has — is neither: it is nobody's, and on its way out.
 */
internal fun String.setAsideOrGone(): Boolean =
    startsWith("files/downloads") || (startsWith("files/profiles/") && !startsWith("files/profiles/old-"))

private const val PreferencesSuffix = ".preferences_pb"
private const val RegistryFileName = "profiles$PreferencesSuffix"

/** The keys and values of a preferences file, in the order of the keys. */
internal suspend fun readPreferences(file: File): Map<String, Any> =
    file.opened { stored -> stored.data.first().asMap().mapKeys { it.key.name }.toSortedMap() }

internal suspend fun OldDevice.registryFacts(cipher: TokenCipher): RegistryFacts {
    val start = MigrationStart(this, cipher)
    val registry = start.registry
    registry.load()
    return RegistryFacts(
        state = registry.state.value,
        current = registry.current.value?.id,
        listed = registry.listedIds(),
        moving = registry.beingMoved(),
        removing = registry.beingRemoved(),
    ).also { start.end() }
}

/** Taken between two starts: no file of the device is open. */
internal suspend fun OldDevice.snapshot(cipher: TokenCipher): DeviceSnapshot {
    val root = checkNotNull(filesDir.parentFile)
    val files = root.walkTopDown().filter { it != root && it.name != RegistryFileName }.associate { file ->
        file.relativeTo(root).path to when {
            file.isDirectory -> "<folder>"
            file.name.endsWith(PreferencesSuffix) -> readPreferences(file).toString()
            else -> file.readText()
        }
    }
    return DeviceSnapshot(
        files = files.toSortedMap(),
        registry = registryFacts(cipher),
        lastBook = getSharedPreferences("player_last_book", Context.MODE_PRIVATE).all,
        databases = databaseList().filter { "exoplayer" in it },
    )
}
