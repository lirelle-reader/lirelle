package app.tfdn.lirelle.profile

import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.TokenCipher
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.TestServer
import app.tfdn.lirelle.signout.installFakeAndroidKeystore
import app.tfdn.lirelle.until
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Another server than [TestServer], as unreachable: the same machine under another name. */
internal const val OtherServer = "https://localhost:9"

/** The access token of the account [userId] of these tests, as its requests carry it. */
internal fun bearerOf(userId: Long) = "Bearer access-$userId"

/** Where the server keeps the picture of the account [userId], as it gives it. */
internal fun pictureUrlOf(userId: Long) = "/api/v1/users/$userId/avatar"

/** The profile [TwoListedProfilesApplication] lists beside the current one: the account 1, on [TestServer]. */
internal const val FirstProfile = "first"

/** The profile it lists as the current one: the account 2, on [OtherServer]. */
internal const val SecondProfile = "second"

/** When the tokens written before the start expire: later than any run of these tests. */
private const val FarAway = "2099-01-01T00:00:00Z"

/**
 * The application of a device whose registry lists two ready profiles when it starts, [SecondProfile] the
 * current one. The application opens the current profile as it starts, before a test can sign anybody in, so
 * the registry is written as a file, before anything of the application runs, the way a start finds it. What
 * the application does with it from there on is its own doing. A test names this as its application
 * (`@Config(application = …)`).
 *
 * The account ids are written as numbers, as the version before wrote them, which this one still reads. The
 * profiles' data is of the current format, unless a subclass says otherwise ([formatOf]).
 */
internal open class TwoListedProfilesApplication : LirelleApplication() {

    override fun onCreate() {
        installFakeAndroidKeystore()
        val cipher = KeystoreTokenCipher()
        val listed = listOf(entry(FirstProfile, 1, TestServer, cipher), entry(SecondProfile, 2, OtherServer, cipher))
        val registry = """{"profiles":[${listed.joinToString(",")}],"current":"$SecondProfile"}"""
        runBlocking {
            writePreferences(this@TwoListedProfilesApplication, "profiles", mapOf("profiles" to registry))
            prepare()
        }
        super.onCreate()
    }

    /** The format of the profile [id]'s data, as the registry says it; null says none, as before it was kept. */
    protected open fun formatOf(id: String): Int? = CurrentDataFormat

    /** Whether the accounts have a picture, at [pictureUrlOf]; none by default, and none is fetched then. */
    protected open val pictured: Boolean = false

    /** What else the device holds before the start, once the registry is written. */
    protected open suspend fun prepare() = Unit

    /** A ready profile as the registry writes it: only its tokens are encrypted. */
    private fun entry(id: String, userId: Long, server: String, cipher: TokenCipher): String {
        val tokens = """{"accessToken":"access-$userId","accessTokenExpiresAt":"$FarAway",""" +
            """"refreshToken":"refresh-$userId","refreshTokenExpiresAt":"$FarAway"}"""
        val picture = ""","avatarUrl":"${pictureUrlOf(userId)}"""".takeIf { pictured }.orEmpty()
        val user = """{"id":$userId,"username":"user$userId","name":"User $userId"$picture}"""
        val format = formatOf(id)?.let { ""","dataFormat":$it""" }.orEmpty()
        return """{"id":"$id","kind":"BookOrbit","session":{"serverUrl":"$server","sessionId":$userId,""" +
            """"user":$user,"encryptedTokens":"${cipher.encrypt(tokens)}"},"state":"ready"$format}"""
    }
}

/**
 * Two profiles open at once in the application's real graph, which the app itself never does — it opens the
 * current one only: the second is the one the app shows, the first is opened beside it.
 */
internal class TwoProfiles(private val device: TestDevice) {

    private val data get() = device.data
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val beside = mutableListOf<GraphView>()

    /**
     * The two profiles [TwoListedProfilesApplication] lists: the second is the one the app opened at its
     * start, the first is opened beside it.
     *
     * @return the first profile, which is not the current one, and the second, which is.
     */
    suspend fun open(): Pair<GraphView, GraphView> {
        val second = awaited("the current profile of the registry is open") { data.openProfiles().current.value }
        check(second.profileId == SecondProfile) { "The test's application must list two profiles" }
        return openBeside(FirstProfile) to GraphView(second)
    }

    /**
     * Two profiles on servers that exist only once the test runs, which no registry written before the start
     * can name: both are made straight from what makes a profile's objects, beside the one the app shows,
     * and the registry lists neither. They are whole profiles all the same, with their own folders and
     * clients; only what they would write to the registry — tokens renewed — goes nowhere, and their folders
     * go when they are closed.
     *
     * @return the profile of the account 3 on [firstServer], and that of the account 4 on [secondServer].
     */
    suspend fun openOn(firstServer: String, secondServer: String): Pair<GraphView, GraphView> {
        suspend fun unlisted(userId: Long, server: String) =
            openUnlisted(Profile("unlisted-$userId", ServerKind.BookOrbit, session(userId, server)))
        return unlisted(userId = 3, firstServer) to unlisted(userId = 4, secondServer)
    }

    /**
     * The objects of [profile], which the registry does not list, made beside the one the app shows, as
     * [openOn] makes its own: of whatever kind it is.
     */
    suspend fun openUnlisted(profile: Profile): GraphView = GraphView(opened(profile)).also { beside += it }

    /** The objects of [profile], made by what makes them in the app; its data is of the current format here. */
    private suspend fun opened(profile: Profile) =
        checkNotNull(data.profileGraphFactory().open(profile)) { "The profile's data could not be started again" }

    /**
     * Signs [userId] in on [server], as the sign-in screen does, whoever was signed in before.
     *
     * @return the profile the app shows from then on.
     */
    suspend fun show(userId: Long, server: String = TestServer): GraphView {
        data.sessions().save(session(userId, server))
        until("the profile of the account $userId is shown") { shownUser() == "$userId" }
        return GraphView(checkNotNull(data.openProfiles().current.value))
    }

    /** The objects of the profile [id], which the registry lists and the app is not showing, made anew. */
    suspend fun openBeside(id: String): GraphView {
        val profile = checkNotNull(data.profileStore().profile(id)) { "The profile is not listed" }
        return GraphView(opened(profile)).also { beside += it }
    }

    /** Closes a profile opened beside: when this returns its objects were thrown away, as the app does it. */
    suspend fun close(view: GraphView) {
        beside -= view
        val closing = background.async { data.profileGraphFactory().close(view.graph, background) }
        until("the profile is closed") { closing.isCompleted }
        closing.await()
    }

    /** Leaves nothing of what was opened beside running into the next test. */
    suspend fun closeAll() {
        beside.toList().forEach { close(it) }
        background.cancel()
    }

    private fun shownUser(): String? =
        (data.openProfiles().shown.value.state as? SessionState.SignedIn)?.session?.user?.id

    private fun session(userId: Long, server: String) = bookOrbitSession(
        serverUrl = server,
        sessionId = userId,
        tokens = Tokens("access-$userId", Instant.MAX, "refresh-$userId", Instant.MAX),
        user = UserProfile("$userId", "user$userId", "User $userId", email = null),
    )
}
