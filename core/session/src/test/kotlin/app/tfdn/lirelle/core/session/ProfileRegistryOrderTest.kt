package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.otherSession
import app.tfdn.lirelle.core.session.Sample.session
import app.tfdn.lirelle.core.session.Sample.tokens
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The order the registry keeps between its changes, on real threads: one change at a time, each decided from
 * what the one before published, none before the first read, and none left half done by a cancelled caller.
 */
class ProfileRegistryOrderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var counter = 0

    private val dataStore by lazy {
        GatedDataStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "profiles.preferences_pb") },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private fun registry(store: DataStore<Preferences> = dataStore) =
        DataStoreProfileRegistry(store, FakeTokenCipher()) { "p${counter++}" }

    private suspend fun loaded() = registry().also { it.load() }

    @Test
    fun `a failed initial read loads as signed out`() = runBlocking<Unit> {
        val registry = registry(UnreadableDataStore())

        withTimeout(LoadTimeoutMillis) { registry.load() }

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(serverUrl = null, reason = null))
        assertThat(registry.current.value).isNull()
    }

    @Test
    fun `a profile with a malformed timestamp is signed out and left to be removed`() = runBlocking<Unit> {
        loaded().save(session)
        // FakeTokenCipher stores the token JSON reversed, so the timestamp is reversed too.
        val validTimestamp = tokens.accessTokenExpiresAt.toString().reversed()
        dataStore.edit {
            val raw = it[RegistryKeys.Profiles]!!
            assertThat(raw).contains(validTimestamp)
            it[RegistryKeys.Profiles] = raw.replace(validTimestamp, "not-a-date".reversed())
        }

        val restarted = registry()
        withTimeout(LoadTimeoutMillis) { restarted.load() }

        assertThat(restarted.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.profile("p0")).isNull()
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `a change asked before load applies after it`() = runBlocking<Unit> {
        val registry = registry()

        val save = launch(Dispatchers.Default) { registry.save(session) }
        withTimeoutOrNull(RaceWindowMillis) { save.join() }

        assertThat(save.isCompleted).isFalse()
        assertThat(registry.state.value).isEqualTo(SessionState.Loading)
        assertThat(dataStore.writes).isEqualTo(0)

        registry.load()
        save.join()

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(loaded().state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `updateTokens after clear leaves the profile removed on disk`() = runBlocking<Unit> {
        val registry = loaded()
        registry.save(session)

        registry.clearCurrent(SignOutReason.UserRequest)
        registry.renew("p0", tokens.copy(accessToken = "access-3"))

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        val restarted = loaded()
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.profile("p0")).isNull()
    }

    @Test
    fun `updateTokens that started before clear cannot bring the profile back`() = runBlocking<Unit> {
        val registry = loaded()
        registry.save(session)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dataStore.beforeNextWrite = {
            reached.complete(Unit)
            release.await()
        }

        // updateTokens has seen the profile and is about to write when clear is called.
        val update = launch(Dispatchers.Default) {
            registry.renew("p0", tokens.copy(accessToken = "access-2"))
        }
        reached.await()
        val clear = launch(Dispatchers.Default) { registry.clearCurrent(SignOutReason.UserRequest) }
        withTimeoutOrNull(RaceWindowMillis) { clear.join() }
        release.complete(Unit)
        joinAll(update, clear)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.current.value).isNull()
        val restarted = loaded()
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(restarted.beingRemoved()).containsExactly("p0")
    }

    @Test
    fun `interleaved updateTokens and clear leave state and disk in agreement`() = runBlocking(Dispatchers.Default) {
        val registry = loaded()

        repeat(StressRounds) { round ->
            registry.save(session)
            val id = registry.current.value!!.id
            List(StressJobs) { index ->
                launch {
                    if (index == StressJobs / 2) {
                        registry.clearCurrent(SignOutReason.UserRequest)
                    } else {
                        registry.renew(id, tokens.copy(accessToken = "access-$round-$index"))
                    }
                }
            }.joinAll()

            // Whatever the order, a clear ran, and an update after it must be a no-op.
            assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
            assertThat(registry.current.value).isNull()
            val onDisk = loaded()
            assertThat(onDisk.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
            assertThat(onDisk.profile(id)).isNull()
            assertThat(onDisk.beingRemoved()).containsExactly(id)
            registry.forget(id)
        }

        registry.save(session)
        val last = registry.current.value!!.id
        List(StressJobs) { index ->
            launch {
                if (index % 2 == 0) {
                    registry.renew(last, tokens.copy(accessToken = "access-last-$index"))
                } else {
                    registry.clearCurrent(SignOutReason.UserRequest)
                }
            }
        }.joinAll()
        registry.clearCurrent(SignOutReason.UserRequest)

        // The profile was already removed: a later clear finds nobody, and changes nothing.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(loaded().state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `a sign-out that waited behind another for the same profile removes nobody else`() = runBlocking<Unit> {
        val registry = loaded()
        registry.save(session)
        registry.save(otherSession)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dataStore.beforeNextWrite = {
            reached.complete(Unit)
            release.await()
        }

        // Both read p1 as the current profile — two taps — and the second waits while the first is written.
        val first = async(Dispatchers.Default) { registry.clear("p1", SignOutReason.UserRequest) }
        reached.await()
        val second = async(Dispatchers.Default) { registry.clear("p1", SignOutReason.UserRequest) }
        withTimeoutOrNull(RaceWindowMillis) { second.join() }
        release.complete(Unit)

        assertThat(first.await()).isTrue()
        assertThat(second.await()).isFalse()
        // The profile that took its place is whole, and still the current one.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, session))
        assertThat(registry.beingRemoved()).containsExactly("p1")
        val restarted = loaded()
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(restarted.beingRemoved()).containsExactly("p1")
    }

    @Test
    fun `signing out of a profile that is not the current one removes nobody`() = runBlocking<Unit> {
        val registry = loaded()
        registry.save(session)
        registry.save(otherSession)
        val writes = dataStore.writes

        assertThat(registry.clear("p0", SignOutReason.UserRequest)).isFalse()
        assertThat(registry.clear("nobody", SignOutReason.UserRequest)).isFalse()

        assertThat(registry.profiles.value.map { it.id }).containsExactly("p1", "p0").inOrder()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `a caller cancelled while its write commits still publishes what was written`() = runBlocking<Unit> {
        val registry = loaded()
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dataStore.afterNextWrite = {
            written.complete(Unit)
            release.await()
        }

        // The profile is on disk, and the caller is cancelled before the write returns to it.
        val save = launch(Dispatchers.Default) { registry.save(session) }
        written.await()
        save.cancel()
        release.complete(Unit)
        save.join()

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
        assertThat(registry.current.value?.session).isEqualTo(session)
        assertThat(loaded().state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `updateUser with nothing changed writes nothing`() = runBlocking<Unit> {
        val registry = loaded()
        registry.save(session)
        val writes = dataStore.writes

        registry.updateUser("p0", session.user.copy())

        assertThat(dataStore.writes).isEqualTo(writes)
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(session))
    }

    @Test
    fun `removing a profile that is not current survives a restart`() = runBlocking<Unit> {
        dataStore.writeReady(session, otherSession)
        val registry = loaded()

        registry.remove("p0", SignOutReason.UserRequest)

        val restarted = loaded()
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(otherSession))
        assertThat(restarted.current.value?.id).isEqualTo("p1")
        assertThat(restarted.profile("p0")).isNull()
        assertThat(restarted.beingRemoved()).containsExactly("p0")
        assertThat(restarted.listedIds()).containsExactly("p0", "p1")
    }

    private companion object {
        /** A load that hangs fails its test; generous, for a runner that shares its machine. */
        const val LoadTimeoutMillis = 60_000L

        /**
         * How long a change is given to show that it does NOT go through: what is asserted after it is that
         * nothing happened, so a slow machine cannot fail the test, only make it prove less.
         */
        const val RaceWindowMillis = 500L
        const val StressRounds = 100
        const val StressJobs = 8
    }
}
