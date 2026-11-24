package app.tfdn.lirelle.profile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.di.NetworkProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.session.profile.ProfileGraph
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.GraphView
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.signout.profile
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

private const val Reader = 5L
private const val Other = 6L
private val ReadFile = FileId("77")
private const val Halfway = 0.5
private const val AsksAfterExpiry = 10

/** Where the reader's positions are sent. */
private const val PositionsPath = "/reading-progress"

/** The tokens the reader's positions were sent with, each once. */
private fun RecordingServer.positionsSentAs() = authorizationsForPathsEnding(PositionsPath).distinct()

/**
 * A session the server no longer renews costs its profile nothing but the server: on the application's real
 * graph, against a server that refuses every renewal. The profile stays the one shown, with its folders; it
 * asks the server nothing more; what is read meanwhile waits, and leaves when the same account has signed in
 * again — in the very objects the profile had, which are not made anew.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileExpiryTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val server = RecordingServer(refusesRenewals = true)
    private val store get() = device.data.profileStore()

    /** The device has a network: what the profile lacks below is its session, and nothing else. */
    @Before
    fun deviceOnline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
    }

    @After
    fun close(): Unit = runBlocking {
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed and dropped") {
            device.data.openProfiles().current.value == null && device.listed()?.isEmpty() == true
        }
        server.close()
    }

    /** @param accessUntil when the access token ends: one that has ended is renewed before anything is asked. */
    private fun session(userId: Long, access: String, accessUntil: Instant = Instant.MAX) = bookOrbitSession(
        serverUrl = server.address,
        sessionId = userId,
        tokens = Tokens(access, accessUntil, "refresh-$userId", Instant.MAX),
        user = UserProfile("$userId", "user$userId", "User $userId", email = null),
    )

    private fun shownUser(): String? =
        (device.data.openProfiles().shown.value.state as? SessionState.SignedIn)?.session?.user?.id

    /** Signs [session]'s account in, as the sign-in screen does. @return the profile shown from then on. */
    private suspend fun signIn(session: Session): ProfileGraph {
        device.data.sessions().save(session)
        until("the profile of the account ${session.user.id} is shown") { shownUser() == session.user.id }
        return checkNotNull(device.data.openProfiles().current.value)
    }

    /**
     * The reader signs in with an access token that has ended: the profile opens, asks for its account, and
     * the server refuses to renew.
     *
     * @return the profile, once it is marked.
     */
    private suspend fun expired(): ProfileGraph {
        val open = signIn(session(Reader, access = "access-dead", accessUntil = Instant.EPOCH))
        until("the profile is marked as needing sign-in") { store.profile(open.profileId)?.signInNeeded == true }
        return open
    }

    private fun ProfileGraph.online() = GraphView(this).profile<NetworkProfileEntry>().profileNetwork().online.value

    /** What a screen or a task of the profile asks its server, as its account. */
    private suspend fun ProfileGraph.asks(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val client = GraphView(this@asks).profile<ProfileDataOnDevice>().authenticatedClient()
        runCatching { client.newCall(Request.Builder().url(server.url(path)).build()).execute().close() }
    }

    /** A page turned in the reader: kept to be sent, and the sending asked for. */
    private suspend fun ProfileGraph.reads() {
        val reading = GraphView(this).profile<ReaderCoreProfileEntry>()
        reading.readingOutbox().enqueuePosition(LocalPosition(ReadFile, "epubcfi(/6/8)", Halfway, Instant.now()))
        reading.readingOutboxFlusher().kick()
    }

    private suspend fun ProfileGraph.waitingPositions() =
        GraphView(this).profile<ReaderCoreProfileEntry>().readingOutbox().pending().positions.map { it.fileId }

    @Test
    fun `a renewal refused keeps the profile, silent, until its account signs in again in the same objects`(): Unit =
        runBlocking {
            val open = expired()
            val folders = GraphView(open).folders

            // Still the profile shown, listed, whole on the disk; and without a network of its own.
            assertThat(device.data.openProfiles().current.value).isSameInstanceAs(open)
            assertThat(device.data.openProfiles().shown.value.profileId).isEqualTo(open.profileId)
            assertThat(store.profiles.value.map { it.id }).containsExactly(open.profileId)
            assertThat(store.beingRemoved()).isEmpty()
            assertThat(folders.files.exists() && folders.cache.exists()).isTrue()
            until("the profile has no network of its own") { !open.online() }

            // Whatever it asks from now on fails on the device: the server hears nothing, a renewal least of all.
            repeat(AsksAfterExpiry) {
                assertThat(open.asks("/api/v1/probe").exceptionOrNull()).isInstanceOf(IOException::class.java)
            }
            open.reads()
            assertThat(open.waitingPositions()).containsExactly(ReadFile)
            assertThat(server.authorizationsFor("/api/v1/probe")).isEmpty()
            assertThat(server.renewals).isEqualTo(1)
            assertWithMessage("the dead token was never shown").that(server.tokensShown()).isEmpty()

            signIn(session(Reader, access = "access-again"))

            until("the mark has fallen") { store.profile(open.profileId)?.signInNeeded == false }
            until("the profile follows the device's network again") { open.online() }
            // What waited leaves by itself, as the account that signed in again.
            until("the position that waited is sent") { server.positionsSentAs().isNotEmpty() }
            assertThat(server.positionsSentAs()).containsExactly("Bearer access-again")
            assertThat(server.tokensShown()).containsExactly("Bearer access-again")
            assertThat(server.renewals).isEqualTo(1)
            // The same profile, in the same objects: nothing was closed, nothing rebuilt.
            assertThat(device.data.openProfiles().current.value).isSameInstanceAs(open)
            assertThat(device.data.openProfiles().current.value?.component).isSameInstanceAs(open.component)
            assertThat(store.profiles.value.map { it.id }).containsExactly(open.profileId)
        }

    @Test
    fun `another account signing in gets its own profile, and the expired one stays marked`(): Unit = runBlocking {
        val first = expired()
        val folders = GraphView(first).folders

        val second = signIn(session(Other, access = "access-other"))

        assertThat(second.profileId).isNotEqualTo(first.profileId)
        assertThat(store.current.value?.id).isEqualTo(second.profileId)
        assertThat(store.profiles.value.map { it.id to it.signInNeeded })
            .containsExactly(second.profileId to false, first.profileId to true).inOrder()
        assertThat(folders.files.exists()).isTrue()
        assertThat(second.online()).isTrue()
    }
}
