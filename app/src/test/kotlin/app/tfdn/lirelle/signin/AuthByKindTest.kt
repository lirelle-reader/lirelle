package app.tfdn.lirelle.signin

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.LoginOptions
import app.tfdn.lirelle.core.network.OidcProvider
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val Typed = "https://typed.example.com"
private const val Answering = "https://answering.example.com"

/**
 * The screens' one entry to signing in: the address asked of each kind of server in turn, the kind found
 * remembered with it, each sign-in sent to its kind, and a sign-out to the profile's own kind.
 *
 * The app knows one kind for now: two probes stand for two kinds, in the order they are asked, under it.
 */
class AuthByKindTest {

    /** What every fake of this test was asked, in order. */
    private val asked = mutableListOf<String>()
    private val store = MemoryStore()
    private val bookOrbit = RecordingSignIn("bookOrbit", asked)

    private fun probe(name: String, answer: ApiResult<ProbeAnswer>) = ServerProbe { address ->
        asked += "$name probes $address"
        answer
    }

    private fun auth(
        vararg probes: ServerProbe,
        signIns: Map<ServerKind, SourceSignIn> = mapOf(ServerKind.BookOrbit to bookOrbit),
    ) = AuthByKind(store, probes.map { ServerKind.BookOrbit to it }, signIns)

    private val notMine = ApiResult.Success(ProbeAnswer.NotMine)
    private val mine = ApiResult.Success(ProbeAnswer.Mine(Answering))

    @Test
    fun `the kinds are asked in order, and the first that knows the address is remembered with it`() = runTest {
        val auth = auth(probe("first", notMine), probe("second", mine))

        val result = auth.connectToServer(Typed)

        assertThat(result).isEqualTo(ApiResult.Success(Unit))
        assertThat(asked).containsExactly("first probes $Typed", "second probes $Typed").inOrder()
        assertThat(store.remembered).containsExactly(Answering to ServerKind.BookOrbit)
        assertThat(auth.serverUrl.value).isEqualTo(Answering)
        assertThat(auth.serverKind.value).isEqualTo(ServerKind.BookOrbit)
    }

    /** The registry that cannot be written is a failure as before, never a crash of the screen that asked. */
    @Test
    fun `an address that cannot be remembered is a failure to reach the server, and nothing is remembered`() =
        runTest {
            store.failWrites = true

            assertThat(auth(probe("first", mine)).connectToServer(Typed))
                .isEqualTo(ApiResult.Failure(ApiError.ServerUnreachable))
            assertThat(store.remembered).isEmpty()
        }

    @Test
    fun `a kind that knows the address ends the asking`() = runTest {
        val auth = auth(probe("first", mine), probe("second", mine))

        auth.connectToServer(Typed)

        assertThat(asked).containsExactly("first probes $Typed")
    }

    @Test
    fun `an address no kind knows is an unknown server, and nothing is remembered`() = runTest {
        val auth = auth(probe("first", notMine), probe("second", notMine))

        assertThat(auth.connectToServer(Typed)).isEqualTo(ApiResult.Failure(ApiError.UnknownServer))
        assertThat(store.remembered).isEmpty()
    }

    @Test
    fun `a server that cannot be reached at all is not asked again by the next kind`() = runTest {
        val unreachable = ApiResult.Failure.neverSent(ApiError.ServerUnreachable)
        val auth = auth(probe("first", unreachable), probe("second", mine))

        assertThat(auth.connectToServer(Typed)).isEqualTo(unreachable)
        assertThat(asked).containsExactly("first probes $Typed")
        assertThat(store.remembered).isEmpty()
    }

    @Test
    fun `a kind that failed to get an answer leaves the next kind to ask, and is told when none knows it`() =
        runTest {
            val failed = ApiResult.Failure(ApiError.ServerFailed)

            assertThat(auth(probe("first", failed), probe("second", mine)).connectToServer(Typed))
                .isEqualTo(ApiResult.Success(Unit))
            assertThat(auth(probe("first", failed), probe("second", notMine)).connectToServer(Typed))
                .isEqualTo(failed)
        }

    @Test
    fun `each sign-in goes to the kind it is for`() = runTest {
        val auth = auth()
        val provider = OidcProvider("authentik", "Authentik", "client", "openid")

        auth.loginOptions(Typed, ServerKind.BookOrbit)
        auth.signInWithPassword(Typed, ServerKind.BookOrbit, "ann", "secret")
        auth.beginOidc(Typed, ServerKind.BookOrbit, provider)

        assertThat(asked).containsExactly(
            "bookOrbit options $Typed",
            "bookOrbit password $Typed ann",
            "bookOrbit begins $Typed authentik",
        ).inOrder()
    }

    @Test
    fun `a sign-in for a kind without its sign-in here is an unknown server, and nothing is sent`() = runTest {
        val auth = auth(signIns = emptyMap())

        assertThat(auth.loginOptions(Typed, ServerKind.BookOrbit)).isEqualTo(ApiResult.Failure(ApiError.UnknownServer))
        assertThat(auth.signInWithPassword(Typed, ServerKind.BookOrbit, "ann", "secret"))
            .isEqualTo(ApiResult.Failure(ApiError.UnknownServer))
        assertThat(asked).isEmpty()
    }

    @Test
    fun `what a provider's page left goes to the kind the page was for, and to none without its sign-in`() =
        runTest {
            assertThat(auth().completeWithSession(Typed, ServerKind.BookOrbit, "SESSION=s1"))
                .isEqualTo(ApiResult.Success(Unit))
            assertThat(auth(signIns = emptyMap()).completeWithSession(Typed, ServerKind.BookOrbit, "SESSION=s1"))
                .isEqualTo(ApiResult.Failure(ApiError.UnknownServer))

            assertThat(asked).containsExactly("bookOrbit completes a page of $Typed with SESSION=s1")
        }

    @Test
    fun `a browser's redirect goes to the kind whose sign-in waits for it`() = runTest {
        val auth = auth()
        val answer = "bookorbit://oauth2-callback?code=c&state=s"

        assertThat(auth.completeOidc(answer)).isEqualTo(ApiResult.Failure(ApiError.OidcRefused))
        bookOrbit.waitsFor = answer
        assertThat(auth.hasPendingOidc()).isTrue()
        assertThat(auth.answersPendingOidc(answer)).isTrue()
        assertThat(auth.completeOidc(answer)).isEqualTo(ApiResult.Success(Unit))
        auth.cancelOidc()

        assertThat(asked).containsExactly("bookOrbit completes $answer", "bookOrbit cancels").inOrder()
    }

    @Test
    fun `a sign-out goes to the kind of the profile asked about`() = runTest {
        store.current.value = Profile("p0", ServerKind.BookOrbit, Ann)

        auth().signOut("p0")

        assertThat(asked).containsExactly("bookOrbit signs out p0")
    }

    @Test
    fun `a sign-out of a profile that is not the current one sends nothing`() = runTest {
        store.current.value = Profile("p1", ServerKind.BookOrbit, Ann)

        auth().signOut("p0")

        assertThat(asked).isEmpty()
        assertThat(store.cleared).isEmpty()
    }

    /** Its kind is looked at before anything is removed: no other kind could end its credential. */
    @Test
    fun `a profile of a kind without its sign-in here is not signed out of, nor removed`() = runTest {
        store.current.value = Profile("p0", ServerKind.BookOrbit, Ann)

        auth(signIns = emptyMap()).signOut("p0")

        assertThat(store.cleared).isEmpty()
        assertThat(store.current.value?.id).isEqualTo("p0")
    }

    /** No profile is open: the credential is handed over, with its server, to the kind it is of. */
    @Test
    fun `the credential of a profile removed while it slept goes to its kind, and to none without its sign-in`() =
        runTest {
            auth().endCredential(ServerKind.BookOrbit, Typed, Ann.credential)
            auth(signIns = emptyMap()).endCredential(ServerKind.BookOrbit, Typed, Ann.credential)

            assertThat(asked).containsExactly("bookOrbit ends a credential of $Typed")
            assertThat(store.cleared).isEmpty()
        }

    private companion object {
        val Ann = bookOrbitSession(
            serverUrl = Typed,
            sessionId = 1,
            tokens = Tokens("access", Instant.MAX, "refresh", Instant.MAX),
            user = UserProfile(id = "1", username = "ann", name = "Ann", email = null),
        )
    }
}

/** One kind's sign-in, which says what it was asked; it waits for [waitsFor] when that is set. */
private class RecordingSignIn(private val name: String, private val asked: MutableList<String>) : SourceSignIn {
    var waitsFor: String? = null

    override suspend fun loginOptions(serverUrl: String): ApiResult<LoginOptions> {
        asked += "$name options $serverUrl"
        return ApiResult.Success(LoginOptions(passwordEnabled = true, oidcProviders = emptyList()))
    }

    override suspend fun signInWithPassword(serverUrl: String, identifier: String, password: String): ApiResult<Unit> {
        asked += "$name password $serverUrl $identifier"
        return ApiResult.Success(Unit)
    }

    override suspend fun beginOidc(serverUrl: String, provider: OidcProvider): ApiResult<String> {
        asked += "$name begins $serverUrl ${provider.slug}"
        return ApiResult.Success("https://auth.example.com/authorize")
    }

    override suspend fun completeOidc(redirectUri: String): ApiResult<Unit> {
        asked += "$name completes $redirectUri"
        return if (redirectUri == waitsFor) ApiResult.Success(Unit) else ApiResult.Failure(ApiError.OidcRefused)
    }

    override suspend fun completeWithSession(serverUrl: String, cookies: String): ApiResult<Unit> {
        asked += "$name completes a page of $serverUrl with $cookies"
        return ApiResult.Success(Unit)
    }

    override suspend fun hasPendingOidc(): Boolean = waitsFor != null

    override suspend fun answersPendingOidc(redirectUri: String): Boolean = redirectUri == waitsFor

    override suspend fun cancelOidc() {
        asked += "$name cancels"
        waitsFor = null
    }

    override suspend fun signOut(profileId: String) {
        asked += "$name signs out $profileId"
    }

    override suspend fun endCredential(serverUrl: String, credential: Credential) {
        asked += "$name ends a credential of $serverUrl"
    }
}

/** What the dispatcher reads and writes of the registry: who is current, and the server remembered. */
private class MemoryStore : SessionStore {
    override val state = MutableStateFlow<SessionState>(SessionState.SignedOut(serverUrl = null, reason = null))
    override val current = MutableStateFlow<Profile?>(null)
    override val serverUrl = MutableStateFlow<String?>(null)
    override val serverKind = MutableStateFlow<ServerKind?>(null)

    val remembered = mutableListOf<Pair<String, ServerKind>>()
    val cleared = mutableListOf<String>()

    /** Every write fails, as a file that cannot be written. */
    var failWrites = false

    override suspend fun setServerUrl(serverUrl: String, kind: ServerKind) {
        if (failWrites) throw IOException("cannot write")
        remembered += serverUrl to kind
        serverKind.value = kind
        this.serverUrl.value = serverUrl
    }

    override suspend fun save(session: Session, kind: ServerKind): ReplacedSession? = error("not a sign-in here")

    override suspend fun clear(profileId: String, reason: SignOutReason): Boolean {
        cleared += profileId
        return true
    }
}
