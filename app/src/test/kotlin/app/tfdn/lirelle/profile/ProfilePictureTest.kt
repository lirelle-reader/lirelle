package app.tfdn.lirelle.profile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile
import app.tfdn.lirelle.core.testing.bookOrbitSession
import app.tfdn.lirelle.core.testing.save
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

private const val Pictured = 5L
private const val Plain = 6L
private const val Unavailable = 503
private val PictureBytes = byteArrayOf(4, 2)

private fun picturePath(userId: Long) = "/api/v1/users/$userId/avatar"

/**
 * A server that gives each account its picture and is unavailable for everything else, and notes the token
 * each request for a picture carried.
 */
private class PictureServer {
    private val server = MockWebServer()
    val asked = CopyOnWriteArrayList<Pair<String, String?>>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.endsWith("/avatar")) return MockResponse(code = Unavailable)
                asked += request.url.encodedPath to request.headers["Authorization"]
                return MockResponse.Builder()
                    .addHeader("Content-Type", "image/jpeg")
                    .body(Buffer().write(PictureBytes))
                    .build()
            }
        }
        server.start()
    }

    val address: String get() = server.url("/").toString().trimEnd('/')

    val requests: Int get() = server.requestCount

    fun close() = server.close()
}

/**
 * The picture of an account on the application's real graph: the open profile fetches it as its account, from
 * its own server; the device keeps it while the profile sleeps, and until the profile is removed.
 */
@RunWith(RobolectricTestRunner::class)
class ProfilePictureTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val server = PictureServer()
    private val elsewhere = PictureServer()
    private val avatars get() = device.data.profileAvatars()

    /** The device has a network: the profiles ask their servers. */
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
        elsewhere.close()
    }

    private fun session(userId: Long, on: PictureServer, pictured: Boolean) = bookOrbitSession(
        serverUrl = on.address,
        sessionId = userId,
        tokens = Tokens("access-$userId", Instant.MAX, "refresh-$userId", Instant.MAX),
        user = UserProfile(
            "$userId",
            "user$userId",
            "User $userId",
            email = null,
            avatarUrl = "${picturePath(userId)}?v=1".takeIf { pictured },
        ),
    )

    /** Signs [session]'s account in, as the sign-in screen does. @return the id of the profile shown then. */
    private suspend fun signIn(session: Session): String {
        device.data.sessions().save(session)
        until("the profile of the account ${session.user.id} is shown") {
            val state = device.data.openProfiles().shown.value.state
            (state as? SessionState.SignedIn)?.session?.user?.id == session.user.id
        }
        return checkNotNull(device.data.openProfiles().current.value).profileId
    }

    private suspend fun kept(profileId: String) = avatars.picture(profileId).first()?.readBytes()?.toList()

    @Test
    fun `the open profile fetches its picture as its account, from its own server only`(): Unit = runBlocking {
        val profile = signIn(session(Pictured, on = server, pictured = true))

        until("the picture is kept") { avatars.keptFor(profile) == "${picturePath(Pictured)}?v=1" }

        assertThat(kept(profile)).isEqualTo(PictureBytes.toList())
        assertThat(server.asked).containsExactly(picturePath(Pictured) to "Bearer access-$Pictured")
        assertThat(elsewhere.requests).isEqualTo(0)
    }

    @Test
    fun `a profile that sleeps keeps its picture, and loses it when it is removed`(): Unit = runBlocking {
        val pictured = signIn(session(Pictured, on = server, pictured = true))
        until("the picture is kept") { avatars.keptFor(pictured) != null }

        val plain = signIn(session(Plain, on = elsewhere, pictured = false))

        // The first profile sleeps: none of its objects exist, and its picture is still the device's.
        assertThat(plain).isNotEqualTo(pictured)
        assertThat(kept(pictured)).isEqualTo(PictureBytes.toList())
        assertThat(kept(plain)).isNull()
        // The account without a picture asked for none, and the other server was shown no token of this one.
        assertThat(elsewhere.asked).isEmpty()
        assertThat(server.asked.map { it.second }.distinct()).containsExactly("Bearer access-$Pictured")

        assertThat(device.data.profileStore().removeSleeping(pictured)).isTrue()

        until("the removed profile is dropped") { device.listed() == setOf(plain) }
        assertThat(kept(pictured)).isNull()
        assertThat(avatars.keptFor(pictured)).isNull()
    }

    @Test
    fun `signing out of the open profile deletes its picture`(): Unit = runBlocking {
        val profile = signIn(session(Pictured, on = server, pictured = true))
        until("the picture is kept") { avatars.keptFor(profile) != null }

        device.signOut()

        assertThat(kept(profile)).isNull()
    }
}
