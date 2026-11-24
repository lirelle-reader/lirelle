package app.tfdn.lirelle.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.audio.settings.moveBookSpeeds
import app.tfdn.lirelle.core.comics.settings.moveReadingDirections
import app.tfdn.lirelle.core.library.moveRecentSearches
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private typealias Move = suspend (DataStore<Preferences>, DataStore<Preferences>?) -> Unit

/**
 * A file of the device that mixed the device's settings and the account's, as the old version wrote it, and
 * what each half must hold once the account's part is in the profile's file.
 */
private class Mixed(
    val name: String,
    val move: Move,
    val old: Map<String, String>,
    val device: Map<String, String>,
    val profile: Map<String, String>,
)

/**
 * Each of the three splits, on real files: what an interruption between the copy and the removal leaves, and
 * a second run, end as one run does.
 */
class MigrationSplitsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceFile by lazy { File(folder.root, "device.preferences_pb") }
    private val profileFile by lazy { File(folder.root, "profile.preferences_pb") }
    private val device by lazy { PreferenceDataStoreFactory.create(scope = scope) { deviceFile } }
    private val profile by lazy { PreferenceDataStoreFactory.create(scope = scope) { profileFile } }

    private val mixed = listOf(
        Mixed(
            name = "searches",
            move = ::moveRecentSearches,
            old = OldValues.library,
            device = OldValues.library - "recent_searches",
            profile = mapOf("recent_searches" to """["mars","dune"]"""),
        ),
        Mixed(
            name = "directions",
            move = ::moveReadingDirections,
            old = OldValues.comics,
            device = mapOf("fit" to "Width", "twoPages" to "Always", "background" to "White"),
            profile = OldValues.comics - setOf("fit", "twoPages", "background"),
        ),
        Mixed(
            name = "speeds",
            move = ::moveBookSpeeds,
            old = OldValues.player,
            device = mapOf("player_settings" to OldValues.PlayerDevice),
            profile = mapOf("book_speeds" to "{${OldValues.Speeds}}"),
        ),
    )

    @After
    fun letGoOfTheFiles() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    private suspend fun DataStore<Preferences>.put(values: Map<String, String>) {
        edit { stored ->
            stored.clear()
            values.forEach { (key, value) -> stored[stringPreferencesKey(key)] = value }
        }
    }

    private suspend fun DataStore<Preferences>.values(): Map<String, Any> = data.first().asMap().mapKeys { it.key.name }

    /** Runs [check] for each split, on files emptied first. */
    private fun eachSplit(check: suspend (Mixed) -> Unit) = runBlocking {
        mixed.forEach { split ->
            device.put(emptyMap())
            profile.put(emptyMap())
            check(split)
        }
    }

    @Test
    fun `the account's part goes to the profile's file, and the device's stays as it was`() = eachSplit { split ->
        device.put(split.old)

        split.move(device, profile)

        assertWithMessage("${split.name}: device").that(device.values()).isEqualTo(split.device)
        assertWithMessage("${split.name}: profile").that(profile.values()).isEqualTo(split.profile)
    }

    @Test
    fun `interrupted between the copy and the removal, a second run ends the same`() = eachSplit { split ->
        // Copied into the profile's file, and still in the device's.
        device.put(split.old)
        profile.put(split.profile)

        split.move(device, profile)

        assertWithMessage("${split.name}: device").that(device.values()).isEqualTo(split.device)
        assertWithMessage("${split.name}: profile").that(profile.values()).isEqualTo(split.profile)
    }

    @Test
    fun `once the account's part has left the device's file, nothing is touched again`() = eachSplit { split ->
        device.put(split.old)
        split.move(device, profile)
        // What the profile did since: the split must not bring the old values back over it.
        val since = split.profile.mapValues { "changed" }
        profile.put(since)

        split.move(device, profile)

        assertWithMessage("${split.name}: device").that(device.values()).isEqualTo(split.device)
        assertWithMessage("${split.name}: profile").that(profile.values()).isEqualTo(since)
    }

    @Test
    fun `with no profile the account's part is only removed`() = eachSplit { split ->
        device.put(split.old)

        split.move(device, null)

        assertWithMessage("${split.name}: device").that(device.values()).isEqualTo(split.device)
        assertWithMessage("${split.name}: profile").that(profile.values()).isEmpty()
    }

    @Test
    fun `a device file that holds nothing of the account is not written`() = runBlocking {
        val untouched = File(folder.root, "untouched.preferences_pb")
        val none = PreferenceDataStoreFactory.create(scope = scope) { untouched }

        mixed.forEach { it.move(none, profile) }

        assertThat(untouched.exists()).isFalse()
        assertThat(profileFile.exists()).isFalse()
    }

    @Test
    fun `player settings that are not an object are left for the store to read as it does`() = runBlocking {
        device.put(mapOf("player_settings" to "{not json"))

        moveBookSpeeds(device, profile)

        assertThat(device.values()).isEqualTo(mapOf("player_settings" to "{not json"))
        assertThat(profileFile.exists()).isFalse()
    }
}
