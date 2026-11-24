package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** On real files and real threads, as at the start of the application: nothing here runs in a test's time. */
class SessionAdoptionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cipher = FakeTokenCipher()
    private val sessionFile by lazy { File(folder.root, "session.preferences_pb") }
    private val registryFile by lazy { File(folder.root, "profiles.preferences_pb") }
    private val scopes = mutableListOf<CoroutineScope>()
    private var counter = 0

    /** One start of the application: its registry, read from the file again, and the adoption made from it. */
    private inner class Start(wrap: (DataStore<Preferences>) -> DataStore<Preferences> = { it }) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val dataStore = wrap(PreferenceDataStoreFactory.create(scope = scope) { registryFile })
        val registry = DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }
        val adoption = registry.adoption(sessionFile, registryFile)

        /** The process ends: the registry's file is let go of. */
        fun end(): Unit = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

        fun loaded() = registry.also { runBlocking { it.load() } }
    }

    @After
    fun letGoOfTheFiles(): Unit = runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }

    /** Writes the old file as the store of one session did, then lets go of it. */
    private fun writeOld(vararg values: Pair<String, String>) = runBlocking {
        val writing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = writing) { sessionFile }
            .edit { old -> values.forEach { (key, value) -> old[stringPreferencesKey(key)] = value } }
        writing.coroutineContext.job.cancelAndJoin()
    }

    private fun writeOldSession() =
        writeOld("session" to ProfileCodec(cipher).encodeSession(session), "server_url" to Server)

    @Test
    fun `the old session becomes a profile being moved in, which is listed and neither current nor ready`(): Unit =
        runBlocking {
            writeOldSession()
            val start = Start()
            assertThat(start.adoption.due()).isTrue()
            assertThat(start.adoption.started()).isFalse()

            val old = start.adoption.read()
            val moving = start.adoption.adopt(old)

            assertThat(old.session).isEqualTo(session)
            assertThat(moving).isEqualTo(MovingProfile("p0", Server, userId = "1"))
            assertThat(start.adoption.started()).isTrue()
            val registry = start.registry
            assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
            assertThat(registry.current.value).isNull()
            assertThat(registry.profile("p0")).isNull()
            assertThat(registry.beingMoved()).containsExactly("p0")
            assertThat(registry.beingRemoved()).isEmpty()
            assertThat(registry.listedIds()).containsExactly("p0")
        }

    @Test
    fun `a profile being moved in is found again by the next start, and is still not ready`(): Unit = runBlocking {
        writeOldSession()
        Start().apply { adoption.adopt(adoption.read()) }.end()

        val restarted = Start()

        assertThat(restarted.adoption.due()).isTrue()
        assertThat(restarted.adoption.started()).isTrue()
        assertThat(restarted.adoption.moving()).isEqualTo(MovingProfile("p0", Server, userId = "1"))
        assertThat(restarted.registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.registry.current.value).isNull()
        assertThat(restarted.registry.beingMoved()).containsExactly("p0")
        assertThat(restarted.registry.listedIds()).containsExactly("p0")
    }

    @Test
    fun `a profile being moved in is neither removed nor signed out of`(): Unit = runBlocking {
        writeOldSession()
        val start = Start()
        start.adoption.adopt(start.adoption.read())

        start.registry.remove("p0", SignOutReason.UserRequest)
        start.registry.clearCurrent(SignOutReason.UserRequest)
        start.registry.forget("p0")

        assertThat(start.registry.beingMoved()).containsExactly("p0")
        assertThat(start.registry.beingRemoved()).isEmpty()
    }

    @Test
    fun `settled, the profile is ready and current with the same session, at this start and the next`(): Unit =
        runBlocking {
            writeOldSession()
            val start = Start()
            val moving = checkNotNull(start.adoption.adopt(start.adoption.read()))

            start.adoption.settle(moving.id)

            assertThat(start.registry.state.value).isEqualTo(SessionState.SignedIn(session))
            // What it was given is what the version before the profiles kept: data of the old format, which
            // starts again when the profile is opened.
            assertThat(start.registry.current.value)
                .isEqualTo(Profile("p0", ServerKind.BookOrbit, session, dataFormat = OldDataFormat))
            assertThat(start.registry.beingMoved()).isEmpty()
            assertThat(start.adoption.moving()).isNull()
            start.end()
            val restarted = Start().loaded()
            assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
            assertThat(restarted.beingMoved()).isEmpty()
            assertThat(restarted.listedIds()).containsExactly("p0")
        }

    @Test
    fun `settling twice, or a profile that is not being moved in, changes nothing`(): Unit = runBlocking {
        writeOldSession()
        val start = Start()
        val moving = checkNotNull(start.adoption.adopt(start.adoption.read()))
        start.adoption.settle(moving.id)
        start.registry.clearCurrent(SignOutReason.UserRequest)

        start.adoption.settle(moving.id)
        start.adoption.settle("nobody")

        assertThat(start.registry.current.value).isNull()
        assertThat(start.registry.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a profile whose tokens are lost while it is moved in is to be removed, not opened`(): Unit = runBlocking {
        writeOldSession()
        val start = Start()
        val moving = checkNotNull(start.adoption.adopt(start.adoption.read()))
        cipher.broken = true

        start.adoption.settle(moving.id)

        assertThat(start.registry.current.value).isNull()
        assertThat(start.registry.beingMoved()).isEmpty()
        assertThat(start.registry.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `an old file without a session gives no profile, and its server is kept`(): Unit = runBlocking {
        writeOld("server_url" to Server)
        val start = Start()

        val old = start.adoption.read()
        val moving = start.adoption.adopt(old)

        assertThat(old.session).isNull()
        assertThat(moving).isNull()
        assertThat(start.adoption.started()).isTrue()
        assertThat(start.registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(start.registry.listedIds()).isEmpty()
        start.end()
        assertThat(Start().loaded().state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `an old file that remembers nothing still makes the registry's file`(): Unit = runBlocking {
        writeOld("other" to "value")
        val start = Start()

        assertThat(start.adoption.adopt(start.adoption.read())).isNull()

        assertThat(start.adoption.started()).isTrue()
        assertThat(start.registry.state.value).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
    }

    @Test
    fun `a session whose tokens can no longer be read is nobody's`(): Unit = runBlocking {
        writeOldSession()
        cipher.broken = true

        val old = Start().adoption.read()

        assertThat(old.session).isNull()
    }

    @Test
    fun `a keystore that fails for now does not say that nobody was signed in`() {
        writeOldSession()
        cipher.failingForNow = true
        val start = Start()

        assertThrows(IOException::class.java) { runBlocking { start.adoption.read() } }

        // Nothing was decided: the next start reads the same file, and finds its session.
        assertThat(start.adoption.started()).isFalse()
        cipher.failingForNow = false
        assertThat(runBlocking { start.adoption.read() }.session).isEqualTo(session)
    }

    @Test
    fun `a profile whose tokens cannot be read for now stays being moved in, and is settled later`(): Unit =
        runBlocking {
            writeOldSession()
            val start = Start()
            val moving = checkNotNull(start.adoption.adopt(start.adoption.read()))
            cipher.failingForNow = true

            assertThrows(IOException::class.java) { runBlocking { start.adoption.settle(moving.id) } }

            assertThat(start.registry.beingMoved()).containsExactly("p0")
            assertThat(start.registry.beingRemoved()).isEmpty()
            start.end()
            // Neither does the next start give it up for that, while the keystore still fails.
            val restarted = Start()
            assertThat(restarted.loaded().beingMoved()).containsExactly("p0")
            assertThat(restarted.registry.beingRemoved()).isEmpty()
            restarted.end()

            cipher.failingForNow = false
            val later = Start()
            later.adoption.settle(checkNotNull(later.adoption.moving()).id)

            assertThat(later.registry.current.value)
                .isEqualTo(Profile("p0", ServerKind.BookOrbit, session, dataFormat = OldDataFormat))
        }

    @Test
    fun `a sign-in waiting for its redirect is still waiting afterwards`(): Unit = runBlocking {
        val request = PendingOidcRequest(Server, "provider", "state", "verifier", "nonce")
        val kept = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        DataStorePendingOidcStore(PreferenceDataStoreFactory.create(scope = kept) { sessionFile }, cipher).save(request)
        kept.coroutineContext.job.cancelAndJoin()
        val start = Start()

        start.adoption.adopt(start.adoption.read())

        assertThat(DataStorePendingOidcStore(start.dataStore, cipher).peek()).isEqualTo(request)
    }

    @Test
    fun `the old file is deleted at the end, and nothing is due any more`(): Unit = runBlocking {
        writeOldSession()
        val start = Start()
        start.adoption.settle(checkNotNull(start.adoption.adopt(start.adoption.read())).id)

        start.adoption.finish()
        start.adoption.finish()

        assertThat(sessionFile.exists()).isFalse()
        assertThat(start.adoption.due()).isFalse()
        assertThat(start.registry.state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `what a write cut short left beside the old file goes with it, and nothing else does`(): Unit = runBlocking {
        writeOldSession()
        val scratch = File(folder.root, "session.preferences_pb.tmp").apply { writeText("scratch") }
        val start = Start()
        start.adoption.settle(checkNotNull(start.adoption.adopt(start.adoption.read())).id)

        start.adoption.finish()

        assertThat(scratch.exists()).isFalse()
        assertThat(sessionFile.exists()).isFalse()
        assertThat(registryFile.exists()).isTrue()
    }

    @Test
    fun `a registry that could not be read does not say whether a profile is being moved in`() {
        writeOldSession()
        Start().apply { runBlocking { adoption.adopt(adoption.read()) } }.end()
        val restarted = Start { GatedDataStore(it).apply { failNextRead = true } }

        assertThrows(IOException::class.java) { runBlocking { restarted.adoption.moving() } }
    }
}
