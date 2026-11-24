package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.di.NetworkProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.session.DataStoreProfileRegistry
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.TestServer
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.installFakeAndroidKeystore
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.signout.removeFakeAndroidKeystore
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Reader = 7L
private val ReadFile = FileId("79")
private const val Halfway = 0.5
private const val Place = "epubcfi(/6/8)"
private const val AsksWhileMarked = 10
private const val FarAway = "2099-01-01T00:00:00Z"
private const val LongAgo = "1970-01-01T00:00:00Z"

/** Where the reader's positions are sent. */
private const val PositionsPath = "/reading-progress"

/** The profile [MarkedProfileApplication] lists first: the one used last, which waits for a sign-in. */
internal const val MarkedProfile = "marked"

/** The profile it lists after it, on [TestServer]: one that sleeps, and waits for nothing. */
internal const val RestingProfile = "resting"

/**
 * The application of a device that was left with two profiles, the one used last waiting for a sign-in: the
 * registry is written as a file before anything of the application runs, the way the next start finds it, in
 * order of use. The marked profile's server listens ([server]) and the device has a network, and its access
 * token has ended: a profile that was not marked would ask for a new one as soon as it opens.
 */
internal class MarkedProfileApplication : LirelleApplication() {

    override fun onCreate() {
        installFakeAndroidKeystore()
        deviceOnline()
        val listening = RecordingServer().also { server = it }
        val cipher = KeystoreTokenCipher()
        val marked = entry(MarkedProfile, Reader, listening.address, cipher.encrypt(tokens(Reader, LongAgo)), true)
        val resting = entry(RestingProfile, 1, TestServer, cipher.encrypt(tokens(1, FarAway)), false)
        val registry = """{"profiles":[$marked,$resting],"current":"$MarkedProfile"}"""
        runBlocking { writePreferences(this@MarkedProfileApplication, "profiles", mapOf("profiles" to registry)) }
        super.onCreate()
    }

    private fun tokens(userId: Long, accessUntil: String) =
        """{"accessToken":"access-$userId","accessTokenExpiresAt":"$accessUntil",""" +
            """"refreshToken":"refresh-$userId","refreshTokenExpiresAt":"$FarAway"}"""

    /** A ready profile as the registry writes it: only its tokens are encrypted. */
    private fun entry(id: String, userId: Long, address: String, encrypted: String, marked: Boolean): String {
        val user = """{"id":$userId,"username":"user$userId","name":"User $userId"}"""
        return """{"id":"$id","kind":"BookOrbit","session":{"serverUrl":"$address","sessionId":$userId,""" +
            """"user":$user,"encryptedTokens":"$encrypted"},"state":"ready","signInNeeded":$marked}"""
    }

    companion object {
        /** The server of the marked profile, started before the application: what it hears, the app sent. */
        lateinit var server: RecordingServer
    }
}

/**
 * What a restart finds of several profiles: the order they were used in, so that the app reopens the one used
 * last, and the mark of a profile that waits for a sign-in. A process cannot be started twice in one test: the
 * registry is restarted over the file the application wrote, and the application is started on a registry
 * written before it runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwoListedProfilesApplication::class)
class ProfileRestartTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val profiles = TwoProfiles(device)
    private val store get() = device.data.profileStore()
    private val restarts = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close(): Unit = runBlocking {
        profiles.closeAll()
        restarts.cancel()
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") { device.open() == null && device.listed()?.isEmpty() == true }
        removeFakeAndroidKeystore()
    }

    /**
     * A registry started on what the application's own has written so far, with the device's own keystore: a
     * copy of its file, since the application still holds the file itself.
     */
    private suspend fun registryRestarted(): DataStoreProfileRegistry {
        val copy = File(folder.newFolder(), "profiles.preferences_pb")
        context.preferencesDataStoreFile("profiles").copyTo(copy)
        val dataStore = PreferenceDataStoreFactory.create(scope = restarts) { copy }
        return DataStoreProfileRegistry(dataStore, KeystoreTokenCipher()) { error("No profile is made at a start") }
            .also { it.load() }
    }

    private fun order() = store.profiles.value.map { it.id }

    private fun GraphView.online() = profile<NetworkProfileEntry>().profileNetwork().online.value

    /** What a screen or a task of the profile asks its server, as its account. */
    private suspend fun GraphView.asks(server: RecordingServer): Result<Unit> = withContext(Dispatchers.IO) {
        val client = profile<ProfileDataOnDevice>().authenticatedClient()
        runCatching { client.newCall(Request.Builder().url(server.url("/api/v1/probe")).build()).execute().close() }
    }

    @Test
    fun `the order of use follows sign-ins and switches, and a registry restarted opens the profile used last`(): Unit =
        runBlocking {
            device.shows(SecondProfile)
            assertThat(order()).containsExactly(SecondProfile, FirstProfile).inOrder()

            // A third account signs in, then a tap on each of the two others.
            val third = profiles.show(userId = 3).id
            assertThat(order()).containsExactly(third, SecondProfile, FirstProfile).inOrder()
            device.switchesTo(FirstProfile)
            assertThat(order()).containsExactly(FirstProfile, third, SecondProfile).inOrder()
            device.switchesTo(SecondProfile)
            assertThat(order()).containsExactly(SecondProfile, FirstProfile, third).inOrder()

            val restarted = registryRestarted()

            assertThat(restarted.profiles.value.map { it.id })
                .containsExactly(SecondProfile, FirstProfile, third).inOrder()
            assertThat(restarted.current.value?.id).isEqualTo(SecondProfile)
            val shown = restarted.state.value as SessionState.SignedIn
            assertThat(shown.session.user.id).isEqualTo("2")
            assertThat(restarted.profiles.value.none { it.signInNeeded }).isTrue()

            // And once more, from another profile: the one in front at the restart is the one it opens.
            device.switchesTo(third)
            assertThat(registryRestarted().current.value?.id).isEqualTo(third)
        }

    @Test
    @Config(application = MarkedProfileApplication::class)
    fun `a profile that waited for a sign-in is opened marked by the next start, and asks its server nothing`(): Unit =
        runBlocking {
            val server = MarkedProfileApplication.server
            try {
                // The start opened the profile used last, the first of the order, although it is marked.
                val open = device.shows(MarkedProfile)
                assertThat(store.profiles.value.map { it.id to it.signInNeeded })
                    .containsExactly(MarkedProfile to true, RestingProfile to false).inOrder()
                assertThat(store.current.value?.signInNeeded).isTrue()
                until("the profile is started") {
                    device.data.deviceSignals().followers == FollowsOfAStartedProfile
                }

                // Its objects are there and work on the device; its server is asked nothing, a renewal least of all.
                val reading = open.profile<ReaderCoreProfileEntry>()
                reading.readingPositionStore().put(LocalPosition(ReadFile, Place, Halfway, Instant.EPOCH))
                assertThat(reading.readingPositionStore().get(ReadFile)?.cfi).isEqualTo(Place)
                assertThat(open.online()).isFalse()
                repeat(AsksWhileMarked) {
                    assertThat(open.asks(server).exceptionOrNull()).isInstanceOf(IOException::class.java)
                }
                reading.readingOutbox().enqueuePosition(LocalPosition(ReadFile, Place, Halfway, Instant.now()))
                reading.readingOutboxFlusher().kick()
                assertThat(reading.readingOutbox().pending().positions.map { it.fileId }).containsExactly(ReadFile)
                assertWithMessage("requests heard from a profile that waits for a sign-in")
                    .that(server.heard).isEqualTo(0)

                // The same account signs in again: the silence was the mark's, and what waited leaves.
                device.data.sessions().save(signedInAgain(server.address))
                until("the mark has fallen") { store.profile(MarkedProfile)?.signInNeeded == false }
                until("the place that waited is sent") {
                    "Bearer access-again" in server.authorizationsForPathsEnding(PositionsPath)
                }
                assertThat(server.tokensShown()).containsExactly("Bearer access-again")
                assertThat(server.renewals).isEqualTo(0)
                assertThat(device.open()).isSameInstanceAs(open.graph)
            } finally {
                server.close()
            }
        }

    private fun signedInAgain(address: String) = bookOrbitSession(
        serverUrl = address,
        sessionId = Reader,
        tokens = Tokens("access-again", Instant.MAX, "refresh-again", Instant.MAX),
        user = UserProfile("$Reader", "user$Reader", "User $Reader", email = null),
    )
}
