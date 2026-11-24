package app.tfdn.lirelle.core.session.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.DataStoreProfileRegistry
import app.tfdn.lirelle.core.session.FakeTokenCipher
import app.tfdn.lirelle.core.session.OldDataFormat
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ProfileCodec
import app.tfdn.lirelle.core.session.StoredRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Starting a profile's data again writes only its format: its tokens are not encrypted again, so a keystore
 * that will not encrypt at that moment — it throws, which is no failure to write a file — neither stops the
 * start on a profile that cannot be opened nor escapes a switch.
 */
class ProfileDataResetKeystoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryFile by lazy { File(folder.root, "profiles.preferences_pb") }
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { registryFile } }
    private val roots by lazy { ProfileRoots(folder.newFolder("files"), folder.newFolder("cache")) }
    private val avatars by lazy { FileProfileAvatars(folder.newFolder("avatars"), Dispatchers.Unconfined) }
    private val cipher = FakeTokenCipher()
    private val warnings = mutableListOf<String>()

    private class Built(val session: ProfileSession, val scope: CoroutineScope) : ProfileComponent

    private val life = object : ProfileLifeEntry {
        override fun starters() = emptySet<ProfileStarter>()

        override fun closers() = emptySet<ProfileCloser>()
    }

    @After
    fun close() = scope.cancel()

    /** The registry lists [profiles], the first of them current, written with a keystore that answered. */
    private suspend fun written(vararg profiles: Profile) {
        val codec = ProfileCodec(FakeTokenCipher())
        val stored = profiles.map { codec.encode(it) }
        dataStore.edit { it[stringPreferencesKey("profiles")] = codec.encode(StoredRegistry(stored, stored[0].id)) }
    }

    private fun graphs(registry: DataStoreProfileRegistry) = ProfileGraphs(
        registry,
        registry,
        roots,
        avatars,
        GraphMaking(
            build = { session, _, scope -> Built(session, scope) },
            lifeOf = { life },
            warn = { message, _ -> synchronized(warnings) { warnings += message } },
        ),
        registry,
    )

    private fun registry() = DataStoreProfileRegistry(dataStore, cipher) { error("No profile is made here") }

    @Test
    fun `a keystore that will not encrypt does not keep a profile of the old format from opening at the start`() =
        runBlocking {
            written(profile("p7", dataFormat = OldDataFormat))
            File(roots.of("p7").files, "datastore/reading.preferences_pb").apply { parentFile?.mkdirs() }
                .writeText("positions")
            cipher.failingToEncrypt = true
            val registry = registry()
            val graphs = graphs(registry)

            graphs.open(scope)

            assertThat(graphs.current.value?.profileId).isEqualTo("p7")
            assertThat(graphs.shown.value.updateUnfinished).isFalse()
            assertThat(registry.profile("p7")?.dataFormat).isEqualTo(CurrentDataFormat)
            assertThat(roots.of("p7").files.walkTopDown().filter { it.isFile }.toList()).isEmpty()
            assertThat(warnings).isEmpty()
            // Written as it was, with the format beside it: the next start reads the same tokens.
            cipher.failingToEncrypt = false
            val restarted = registry().also { it.load() }
            assertThat(restarted.profile("p7")?.dataFormat).isEqualTo(CurrentDataFormat)
            assertThat(restarted.profile("p7")?.session).isEqualTo(session())
        }

    @Test
    fun `a keystore that will not encrypt does not stop a switch to a profile of the old format`() = runBlocking {
        written(profile("p7"), profile("p8", userId = 2, dataFormat = OldDataFormat))
        val registry = registry()
        val graphs = graphs(registry)
        graphs.open(scope)
        cipher.failingToEncrypt = true

        registry.switchTo("p8")

        withTimeout(TimeoutMillis) { graphs.current.first { it?.profileId == "p8" } }
        assertThat(registry.profile("p8")?.dataFormat).isEqualTo(CurrentDataFormat)
        assertThat(registry.profile("p8")?.session).isEqualTo(session(userId = 2))
        assertThat(warnings).isEmpty()
    }

    private companion object {
        const val TimeoutMillis = 5_000L
    }
}
