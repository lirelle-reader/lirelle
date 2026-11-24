package app.tfdn.lirelle.signin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val IdentityRoute = "/api/v1/server-identity"
private const val ProvidersRoute = "/api/v1/oauth2/providers"
private const val AccountRoute = "/api/v2/users/me"

/**
 * The app's own sign-in, on its real graph: an address is asked of BookOrbit first, then of Komga, the kind found
 * remembered with it; a Komga account signed in by its password becomes a Komga profile, opened with Komga's
 * objects.
 */
@RunWith(RobolectricTestRunner::class)
class KindDetectionTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val server = MockWebServer()

    /** The paths asked, in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val auth get() = device.data.auth()

    @After
    fun close(): Unit = runBlocking {
        while (device.data.sessions().current.value != null) {
            device.data.sessions().clearCurrent(SignOutReason.UserRequest)
        }
        until("every profile is closed") { device.data.openProfiles().current.value == null }
        server.close()
    }

    private fun json(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse {
        val all = listOf("Content-Type" to "application/json") + headers
        return MockResponse(code = code, headers = headersOf(*all.flatMap { it.toList() }.toTypedArray()), body = body)
    }

    /** A Komga server: it refuses what it does not know, lists its providers, and challenges for Basic. */
    private fun komga() = serve { request ->
        val challenge = json("""{"status":401}""", 401, "WWW-Authenticate" to "Basic realm=\"Realm\"")
        when (request.url.encodedPath) {
            ProvidersRoute -> json("""[{"name":"Authentik","registrationId":"authentik"}]""")
            AccountRoute -> if (request.headers["Authorization"] == null) {
                challenge
            } else {
                json("""{"id":"0B5Y","email":"ann@example.com","roles":["USER"]}""", 200, "X-Auth-Token" to "s")
            }
            "/api/v2/users/me/api-keys" -> json("""{"id":"0K3Y","userId":"0B5Y","key":"k3y","comment":"c"}""")
            "/api/logout" -> MockResponse(code = 204)
            else -> challenge
        }
    }

    private fun serve(answer: (RecordedRequest) -> MockResponse): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += request.url.encodedPath
                return answer(request)
            }
        }
        server.start()
        return server.url("/").toString().trimEnd('/')
    }

    @Test
    fun `a Komga server is asked of BookOrbit first, then found to be Komga's and remembered so`(): Unit =
        runBlocking {
            val address = komga()

            assertThat(auth.connectToServer(address)).isEqualTo(ApiResult.Success(Unit))

            assertThat(asked).containsExactly(IdentityRoute, ProvidersRoute, AccountRoute).inOrder()
            assertThat(auth.serverUrl.value).isEqualTo(address)
            assertThat(auth.serverKind.value).isEqualTo(ServerKind.Komga)
        }

    @Test
    fun `a BookOrbit server is found by BookOrbit, and Komga is not asked`(): Unit = runBlocking {
        val address = serve { json("""{"product":"bookorbit","protocolVersion":1,"instanceId":"i"}""") }

        assertThat(auth.connectToServer(address)).isEqualTo(ApiResult.Success(Unit))

        assertThat(asked).containsExactly(IdentityRoute)
        assertThat(auth.serverKind.value).isEqualTo(ServerKind.BookOrbit)
    }

    @Test
    fun `a Komga account signed in by its password becomes a Komga profile, opened with its key`(): Unit =
        runBlocking {
            val address = komga()

            val result = auth.signInWithPassword(address, ServerKind.Komga, "ann@example.com", "secret")

            assertThat(result).isEqualTo(ApiResult.Success(Unit))
            val profile = checkNotNull(device.data.sessions().current.value)
            assertThat(profile.kind).isEqualTo(ServerKind.Komga)
            assertThat(profile.session.credential).isEqualTo(Credential.ApiKey("k3y", "0K3Y"))
            assertThat(profile.session.user.username).isEqualTo("ann@example.com")
            until("the Komga profile is open") { device.data.openProfiles().current.value?.profileId == profile.id }
        }
}
