package app.tfdn.lirelle.core.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.tfdn.lirelle.core.session.Sample.Server
import app.tfdn.lirelle.core.session.Sample.session
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Several accounts live on one device: signing in adds a profile beside the others, the registry remembers in
 * which order they were used, and a profile whose session ended is kept, marked as needing to sign in.
 */
class ProfileRegistrySeveralTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var counter = 0

    /** Three accounts of one server, each with tokens of its own: p0, p1 and p2 when signed in in that order. */
    private val ann = session
    private val bob = account(2)
    private val cid = account(3)

    /** Ann signing in again: a new session of the same account, with a refresh token of its own. */
    private val signedInAgain = ann.copyCredential(
        sessionId = 77,
        tokens = ann.tokens.copy(accessToken = "access-again", refreshToken = "refresh-again"),
    )

    private fun account(userId: Long) = session.copyCredential(
        sessionId = 40 + userId,
        tokens = session.tokens.copy(accessToken = "access-$userId", refreshToken = "refresh-$userId"),
    ).copy(
        user = UserProfile(id = "$userId", username = "user$userId", name = "User $userId", email = null),
    )

    private fun TestScope.dataStore() = GatedDataStore(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "profiles.preferences_pb") },
    )

    /** A new registry over the same store, as after a process restart. Ids go on: p0, p1… */
    private suspend fun loaded(dataStore: DataStore<Preferences>, cipher: TokenCipher = FakeTokenCipher()) =
        DataStoreProfileRegistry(dataStore, cipher) { "p${counter++}" }.also { it.load() }

    private fun DataStoreProfileRegistry.order() = profiles.value.map { it.id }

    /** A keystore that does not answer for the tokens of [silentFor], and reads every other account's. */
    private fun silentFor(silentFor: Session) = object : TokenCipher by FakeTokenCipher() {
        private val real = FakeTokenCipher()

        override fun decryptOrThrow(cipherText: String): String? = real.decrypt(cipherText)?.also {
            if (silentFor.tokens.accessToken in it) throw IOException("The keystore failed")
        }
    }

    @Test
    fun `a second account signs in beside the first, which stays`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)

        registry.save(bob)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(registry.current.value).isEqualTo(Profile("p1", ServerKind.BookOrbit, bob))
        assertThat(registry.profile("p0")?.session).isEqualTo(ann)
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.order()).containsExactly("p1", "p0").inOrder()
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(restarted.profile("p0")?.session).isEqualTo(ann)
        assertThat(restarted.order()).containsExactly("p1", "p0").inOrder()
    }

    @Test
    fun `the same server and account reopen the same profile, which no longer needs to sign in`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob)
        registry.needSignIn("p0", ann.tokens)
        val again = ann.copyCredential(sessionId = 77, tokens = ann.tokens.copy(accessToken = "access-again"))

        registry.save(again)

        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, again))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(again))
        assertThat(registry.listedIds()).containsExactly("p0", "p1")
        assertThat(loaded(dataStore).current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, again))
    }

    @Test
    fun `the profiles are in order of use after sign-ins, switches and removals`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)

        assertThat(registry.order()).isEmpty()
        listOf(ann, bob, cid).forEach { registry.save(it) }
        assertThat(registry.order()).containsExactly("p2", "p1", "p0").inOrder()
        registry.switchTo("p0")
        assertThat(registry.order()).containsExactly("p0", "p2", "p1").inOrder()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(ann))
        // Signing in again to an account that is there uses its profile, as a switch does.
        registry.save(bob)
        assertThat(registry.order()).containsExactly("p1", "p0", "p2").inOrder()
        // Removing one that sleeps moves nobody.
        registry.remove("p0", SignOutReason.UserRequest)
        assertThat(registry.order()).containsExactly("p1", "p2").inOrder()
        registry.save(ann)
        assertThat(registry.order()).containsExactly("p3", "p1", "p2").inOrder()
        registry.remove("p3", SignOutReason.UserRequest)
        assertThat(registry.order()).containsExactly("p1", "p2").inOrder()

        assertThat(loaded(dataStore).order()).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun `switching makes the profile current, and it still is after a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob)

        registry.switchTo("p0")

        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, ann))
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(ann))
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(ann))
        assertThat(restarted.order()).containsExactly("p0", "p1").inOrder()
    }

    @Test
    fun `switching to an unknown, removed or already current profile changes nothing`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob)
        registry.remove("p0", SignOutReason.UserRequest)
        val writes = dataStore.writes

        registry.switchTo("nobody")
        registry.switchTo("p0")
        registry.switchTo("p1")

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(registry.order()).containsExactly("p1")
        assertThat(registry.beingRemoved()).containsExactly("p0")
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `removing the current profile makes the next one current`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        listOf(ann, bob, cid).forEach { registry.save(it) }
        registry.switchTo("p0")

        registry.clearCurrent(SignOutReason.UserRequest)

        // The one used last before it, not the one that signed in last.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(registry.current.value?.id).isEqualTo("p2")
        assertThat(registry.beingRemoved()).containsExactly("p0")
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(restarted.order()).containsExactly("p2", "p1").inOrder()
    }

    @Test
    fun `removing a profile as one that sleeps removes it only while it is not the current one`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        listOf(ann, bob, cid).forEach { registry.save(it) }

        // The current one: it is removed by signing it out, never from here.
        assertThat(registry.removeSleeping("p2")).isFalse()
        assertThat(registry.order()).containsExactly("p2", "p1", "p0").inOrder()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(registry.beingRemoved()).isEmpty()

        assertThat(registry.removeSleeping("p0")).isTrue()
        assertThat(registry.order()).containsExactly("p2", "p1").inOrder()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(registry.beingRemoved()).containsExactly("p0")
        // Gone already, or never there: nothing.
        assertThat(registry.removeSleeping("p0")).isFalse()
        assertThat(registry.removeSleeping("p9")).isFalse()

        // One that slept when the user answered, and is current when the removal is applied.
        registry.switchTo("p1")
        assertThat(registry.removeSleeping("p1")).isFalse()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(loaded(dataStore).order()).containsExactly("p1", "p2").inOrder()
    }

    /**
     * A removal that does not look at who is current would have the current profile removed as one that
     * sleeps, its server not told: the registry offers the sign-out and [ProfileStore.removeSleeping] only.
     */
    @Test
    fun `no removal is offered that does not know whether the profile is the current one`() {
        listOf(ProfileStore::class.java, DataStoreProfileRegistry::class.java).forEach { type ->
            val removals = type.methods.map { it.name }.filter { it.startsWith("remove") }.toSet()

            assertThat(removals).containsExactly("removeSleeping")
        }
    }

    @Test
    fun `removing the last profile signs out and keeps its address`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob.copy(serverUrl = OtherServer))
        registry.remove("p1", SignOutReason.UserRequest)
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(ann))

        registry.remove("p0", SignOutReason.UserRequest)

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, SignOutReason.UserRequest))
        assertThat(registry.current.value).isNull()
        assertThat(registry.order()).isEmpty()
        assertThat(registry.beingRemoved()).containsExactly("p0", "p1")
        assertThat(loaded(dataStore).state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
    }

    @Test
    fun `a profile that needs to sign in is kept, listed and current, and still marked after a restart`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob)

        val marked = registry.needSignIn("p1", bob.tokens)

        assertThat(marked).isEqualTo(Profile("p1", ServerKind.BookOrbit, bob, signInNeeded = true))
        assertThat(registry.current.value).isEqualTo(marked)
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(registry.profiles.value.map { it.signInNeeded }).containsExactly(true, false).inOrder()
        val restarted = loaded(dataStore)
        assertThat(restarted.current.value).isEqualTo(marked)
        assertThat(restarted.profile("p0")?.signInNeeded).isFalse()
        assertThat(restarted.beingRemoved()).isEmpty()
    }

    @Test
    fun `marking a profile that is not current moves nobody`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        listOf(ann, bob, cid).forEach { registry.save(it) }

        val marked = registry.needSignIn("p0", ann.tokens)

        // Being marked is not being used: the session of a sleeping profile may end without anybody moving.
        assertThat(marked).isEqualTo(Profile("p0", ServerKind.BookOrbit, ann, signInNeeded = true))
        assertThat(registry.order()).containsExactly("p2", "p1", "p0").inOrder()
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(registry.profiles.value.map { it.signInNeeded }).containsExactly(false, false, true).inOrder()
        val restarted = loaded(dataStore)
        assertThat(restarted.order()).containsExactly("p2", "p1", "p0").inOrder()
        assertThat(restarted.profile("p0")).isEqualTo(marked)
    }

    @Test
    fun `a profile that needs to sign in keeps its mark when its tokens or its account are written`() = runTest {
        val registry = loaded(dataStore())
        registry.save(ann)
        registry.needSignIn("p0", ann.tokens)

        val renewed = registry.updateTokens("p0", ann.tokens.copy(accessToken = "access-late"), replacing = ann.tokens)
        val renamed = registry.updateUser("p0", ann.user.copy(name = "Ann R."))

        // A renewal that was in flight when the session ended does not sign anybody in again.
        assertThat(renewed?.signInNeeded).isTrue()
        assertThat(renamed?.signInNeeded).isTrue()
    }

    /** A renewal held the old refresh token for seconds; the account signed in again before its answer came. */
    @Test
    fun `a refusal about tokens that are no longer the profile's marks nobody`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(signedInAgain)
        val writes = dataStore.writes

        assertThat(registry.needSignIn("p0", refused = ann.tokens)).isNull()

        assertThat(registry.current.value).isEqualTo(Profile("p0", ServerKind.BookOrbit, signedInAgain))
        assertThat(dataStore.writes).isEqualTo(writes)
        // The same refusal about the tokens it has now is its own.
        assertThat(registry.needSignIn("p0", refused = signedInAgain.tokens)?.signInNeeded).isTrue()
    }

    @Test
    fun `a renewal that answers about tokens that are no longer the profile's writes nothing`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(signedInAgain)
        val writes = dataStore.writes
        val late = ann.tokens.copy(accessToken = "access-late", refreshToken = "refresh-late")

        assertThat(registry.updateTokens("p0", late, replacing = ann.tokens)).isNull()

        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(signedInAgain))
        assertThat(dataStore.writes).isEqualTo(writes)
        assertThat(loaded(dataStore).profile("p0")?.session).isEqualTo(signedInAgain)
        // A renewal of the tokens it has now is written, whatever the access token it was sent with.
        val next = signedInAgain.tokens.copy(accessToken = "access-next", refreshToken = "refresh-next")
        assertThat(registry.updateTokens("p0", next, replacing = signedInAgain.tokens)?.session?.tokens).isEqualTo(next)
    }

    @Test
    fun `marking a profile that is gone, or already marked, does nothing`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        registry.save(bob)
        registry.remove("p0", SignOutReason.UserRequest)
        registry.needSignIn("p1", bob.tokens)
        val writes = dataStore.writes

        assertThat(registry.needSignIn("nobody", ann.tokens)).isNull()
        assertThat(registry.needSignIn("p0", ann.tokens)).isNull()
        assertThat(registry.needSignIn("p1", bob.tokens)).isNull()

        assertThat(registry.profile("p1")?.signInNeeded).isTrue()
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `the current profile that cannot be read at this start gives way to the next`() = runTest {
        val dataStore = dataStore()
        val first = loaded(dataStore)
        listOf(ann, bob, cid).forEach { first.save(it) }
        first.switchTo("p1")
        first.switchTo("p2")
        val writes = dataStore.writes

        val registry = loaded(dataStore, silentFor(cid))

        // The one used last before it.
        assertThat(registry.state.value).isEqualTo(SessionState.SignedIn(bob))
        assertThat(registry.current.value?.id).isEqualTo("p1")
        assertThat(registry.order()).containsExactly("p1", "p0").inOrder()
        assertThat(registry.profile("p2")).isNull()
        assertThat(registry.beingRemoved()).isEmpty()
        assertThat(registry.listedIds()).containsExactly("p0", "p1", "p2")
        // Nothing of it was touched: it is the current one again at a start that can read it.
        assertThat(dataStore.writes).isEqualTo(writes)
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(cid))
        assertThat(restarted.order()).containsExactly("p2", "p1", "p0").inOrder()
    }

    @Test
    fun `a profile that could not be read at this start is listed again once another was used`() = runTest {
        val dataStore = dataStore()
        val first = loaded(dataStore)
        listOf(ann, bob, cid).forEach { first.save(it) }
        val registry = loaded(dataStore, silentFor(cid))

        registry.switchTo("p0")

        assertThat(registry.order()).containsExactly("p0", "p1").inOrder()
        val restarted = loaded(dataStore)
        assertThat(restarted.state.value).isEqualTo(SessionState.SignedIn(ann))
        // Behind the one shown in its place at that start, which was used after it.
        assertThat(restarted.order()).containsExactly("p0", "p1", "p2").inOrder()
    }

    @Test
    fun `the current profile of a file that kept no order is the one used last before a new one`() = runTest {
        val dataStore = dataStore()
        // Listed as they were made, p0 then p1, and p1 is the current one.
        dataStore.writeReady(ann, bob)
        val registry = loaded(dataStore)
        assertThat(registry.order()).containsExactly("p1", "p0").inOrder()
        counter = 2

        registry.save(cid)

        assertThat(registry.order()).containsExactly("p2", "p1", "p0").inOrder()
        assertThat(loaded(dataStore).order()).containsExactly("p2", "p1", "p0").inOrder()
    }

    @Test
    fun `no profile is current when none that is ready can be read`() = runTest {
        val dataStore = dataStore()
        loaded(dataStore).save(ann)

        val registry = loaded(dataStore, silentFor(ann))

        assertThat(registry.state.value).isEqualTo(SessionState.SignedOut(Server, reason = null))
        assertThat(registry.current.value).isNull()
        assertThat(registry.order()).isEmpty()
    }

    @Test
    fun `a registry written before the profiles were several loads with nobody marked and no picture`() = runTest {
        val dataStore = dataStore()
        val tokens = """{"accessToken":"access-1","accessTokenExpiresAt":"2026-11-13T08:15:00Z",""" +
            """"refreshToken":"refresh-1","refreshTokenExpiresAt":"2026-11-20T08:00:00Z"}"""
        val user = """{"id":1,"username":"reader","name":"Ann Reader","email":"ann@example.com"}"""
        val entry = """{"id":"old","kind":"BookOrbit","session":{"serverUrl":"$Server","sessionId":42,""" +
            """"user":$user,"encryptedTokens":${JsonPrimitive(FakeTokenCipher().encrypt(tokens))}}}"""
        dataStore.edit {
            it[RegistryKeys.Profiles] = """{"profiles":[$entry],"current":"old"}"""
            it[RegistryKeys.ServerUrl] = Server
        }
        val writes = dataStore.writes

        val registry = loaded(dataStore)

        // Written before the format of a profile's data was kept: of the old one.
        val read = Profile("old", ServerKind.BookOrbit, session, dataFormat = OldDataFormat)
        assertThat(registry.current.value).isEqualTo(read)
        assertThat(read.signInNeeded).isFalse()
        assertThat(read.session.user.avatarUrl).isNull()
        assertThat(registry.profiles.value).containsExactly(read)
        assertThat(dataStore.writes).isEqualTo(writes)
    }

    @Test
    fun `what this registry writes for a profile nobody marked is what was written before`() = runTest {
        val dataStore = dataStore()

        loaded(dataStore).save(ann)

        val written = dataStore.data.first()[RegistryKeys.Profiles]!!
        assertThat(written).doesNotContain("signInNeeded")
        assertThat(written).doesNotContain("avatarUrl")
    }

    @Test
    fun `the picture of an account is kept with it`() = runTest {
        val dataStore = dataStore()
        val registry = loaded(dataStore)
        registry.save(ann)
        val pictured = ann.user.copy(avatarUrl = "/api/v1/users/1/avatar")

        registry.updateUser("p0", pictured)

        assertThat(loaded(dataStore).current.value?.session?.user).isEqualTo(pictured)
    }

    private companion object {
        const val OtherServer = "https://other.example.com"
    }
}
