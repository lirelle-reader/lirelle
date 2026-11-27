package app.tfdn.lirelle.signin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SignOutReason
import app.tfdn.lirelle.signout.FakeAndroidKeystore
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.signout.clearCurrent
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertThat
import java.util.Base64
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
private const val HealthRoute = "/api/Health"
private const val OidcSettingsRoute = "/api/Settings/oidc"
private const val StatusRoute = "/status"
private const val MeRoute = "/api/me"

/** A made-up JWT of [type] expiring at [exp], in seconds: put together from its parts, never written whole. */
private fun madeUpJwt(type: String, exp: Long): String {
    fun part(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    val payload = """{"userId":"u-1","username":"ann","type":"$type","exp":$exp}"""
    return listOf(part("""{"alg":"HS256","typ":"JWT"}"""), part(payload), part("not-signed")).joinToString(".")
}

/** Long after the tests run, in seconds. */
private const val FarAway = 4_102_444_800L

/**
 * The app's own sign-in, on its real graph: an address is asked of BookOrbit first, then of Komga, then of
 * Audiobookshelf, then of Kavita, the kind found remembered with it; a Komga account signed in by its password becomes
 * a Komga profile, opened with Komga's objects, a Kavita account a Kavita profile, opened with Kavita's, and an
 * Audiobookshelf account an Audiobookshelf profile, opened with Audiobookshelf's.
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

    /**
     * A Kavita server: it knows nothing of the other kinds' routes, says its health in plain text and what it has
     * of an identity provider, and signs an account in to make it a key — which it takes from then on, unless the
     * key is among the [refused]: deleted in its web interface, or expired.
     */
    private fun kavita(refused: Set<String> = emptySet()) = serve { request ->
        when (request.url.encodedPath) {
            HealthRoute -> MockResponse(code = 200, headers = headersOf("Content-Type", "text/plain"), body = "Ok")
            OidcSettingsRoute -> json(
                """{"autoLogin":false,"disablePasswordAuthentication":false,"providerName":"OpenID Connect",""" +
                    """"enabled":false}""",
            )
            "/api/Account/login" -> json("""{"id":7,"username":"ann","kavitaVersion":"0.9.1.4","token":"t0k3n"}""")
            "/api/Account/create-auth-key" -> json("""{"id":42,"key":"k4v1t4k3y","name":"Lirelle"}""")
            "/api/Account" -> if (request.headers["x-api-key"] == "k4v1t4k3y" && "k4v1t4k3y" !in refused) {
                json("""{"id":7,"username":"ann","kavitaVersion":"0.9.1.4"}""")
            } else {
                MockResponse(code = 401, headers = headersOf("WWW-Authenticate", "Bearer"))
            }
            else -> MockResponse(code = 404)
        }
    }

    /**
     * An Audiobookshelf server running [version]: it says what it is at its status, signs an account in with the
     * tokens in the answer, tells the account its access token proves, and answers every other route — the other
     * kinds' among them — with a bare 401.
     */
    private fun audiobookshelf(version: String = "2.38.0") = serve { request ->
        val access = madeUpJwt("access", FarAway)
        when (request.url.encodedPath) {
            StatusRoute -> json(
                """{"app":"audiobookshelf","serverVersion":"$version","isInit":true,"authMethods":["local"],""" +
                    """"authFormData":{"authOpenIDButtonText":""}}""",
            )
            "/login" -> json(
                """{"user":{"id":"u-1","username":"ann","type":"user","permissions":{"download":true},""" +
                    """"accessToken":"$access","refreshToken":"${madeUpJwt("refresh", FarAway)}"}}""",
            )
            MeRoute -> if (request.headers["Authorization"] == "Bearer $access") {
                json("""{"id":"u-1","username":"ann","type":"user","permissions":{"download":true}}""")
            } else {
                MockResponse(code = 401, headers = headersOf("Content-Type", "text/plain"), body = "Unauthorized")
            }
            else -> MockResponse(code = 401, headers = headersOf("Content-Type", "text/plain"), body = "Unauthorized")
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
    fun `a BookOrbit server is found by BookOrbit, and neither Komga nor Kavita is asked`(): Unit = runBlocking {
        val address = serve { json("""{"product":"bookorbit","protocolVersion":1,"instanceId":"i"}""") }

        assertThat(auth.connectToServer(address)).isEqualTo(ApiResult.Success(Unit))

        assertThat(asked).containsExactly(IdentityRoute)
        assertThat(auth.serverKind.value).isEqualTo(ServerKind.BookOrbit)
    }

    @Test
    fun `a Kavita server is asked of BookOrbit, Komga and Audiobookshelf, then found to be Kavita's`(): Unit =
        runBlocking {
            val address = kavita()

            assertThat(auth.connectToServer(address)).isEqualTo(ApiResult.Success(Unit))

            assertThat(asked)
                .containsExactly(IdentityRoute, ProvidersRoute, StatusRoute, HealthRoute, OidcSettingsRoute).inOrder()
            assertThat(auth.serverUrl.value).isEqualTo(address)
            assertThat(auth.serverKind.value).isEqualTo(ServerKind.Kavita)
        }

    @Test
    fun `an Audiobookshelf server is found before Kavita's probe is asked, and remembered so`(): Unit = runBlocking {
        val address = audiobookshelf()

        assertThat(auth.connectToServer(address)).isEqualTo(ApiResult.Success(Unit))

        assertThat(asked).containsExactly(IdentityRoute, ProvidersRoute, StatusRoute).inOrder()
        assertThat(auth.serverUrl.value).isEqualTo(address)
        assertThat(auth.serverKind.value).isEqualTo(ServerKind.Audiobookshelf)
    }

    @Test
    fun `an Audiobookshelf server too old is told so at the address`(): Unit = runBlocking {
        val address = audiobookshelf(version = "2.25.1")

        val result = auth.connectToServer(address)

        assertThat((result as ApiResult.Failure).error)
            .isEqualTo(ApiError.ServerTooOld(ServerKind.Audiobookshelf, "2.25.1", "2.26"))
        assertThat(auth.serverKind.value).isNull()
    }

    @Test
    fun `an Audiobookshelf account signed in by its password becomes an Audiobookshelf profile`(): Unit =
        runBlocking {
            val address = audiobookshelf()

            val result = auth.signInWithPassword(address, ServerKind.Audiobookshelf, "ann", "secret")

            assertThat(result).isEqualTo(ApiResult.Success(Unit))
            val profile = checkNotNull(device.data.sessions().current.value)
            assertThat(profile.kind).isEqualTo(ServerKind.Audiobookshelf)
            assertThat(profile.session.credential).isInstanceOf(Credential.RotatingTokens::class.java)
            assertThat(profile.session.user.username).isEqualTo("ann")
            until("the Audiobookshelf profile is open") {
                device.data.openProfiles().current.value?.profileId == profile.id
            }
            // The open profile reads its account with its access token, which needs no renewal.
            until("the account is read with the profile's token") { MeRoute in asked }
            assertThat(asked).doesNotContain("/auth/refresh")
            assertThat(device.data.sessions().current.value?.signInNeeded).isFalse()
        }

    @Test
    fun `a Kavita account signed in by its password becomes a Kavita profile, opened with its key`(): Unit =
        runBlocking {
            val address = kavita()

            val result = auth.signInWithPassword(address, ServerKind.Kavita, "ann", "secret")

            assertThat(result).isEqualTo(ApiResult.Success(Unit))
            val profile = checkNotNull(device.data.sessions().current.value)
            assertThat(profile.kind).isEqualTo(ServerKind.Kavita)
            assertThat(profile.session.credential).isEqualTo(Credential.ApiKey("k4v1t4k3y", "42"))
            assertThat(profile.session.user.username).isEqualTo("ann")
            until("the Kavita profile is open") { device.data.openProfiles().current.value?.profileId == profile.id }
            // The open profile reads its account again with its key: how a key the server refuses is noticed.
            until("the account is read with the profile's key") { "/api/Account" in asked }
        }

    /**
     * A Kavita key is never renewed, and nothing tells the device it was deleted: the open profile reads its
     * account, the server refuses the key, and the profile is kept, marked.
     */
    @Test
    fun `a Kavita profile whose key the server refuses is kept, and waits for a sign-in`(): Unit = runBlocking {
        val address = kavita(refused = setOf("k4v1t4k3y"))

        assertThat(auth.signInWithPassword(address, ServerKind.Kavita, "ann", "secret"))
            .isEqualTo(ApiResult.Success(Unit))
        val profile = checkNotNull(device.data.sessions().current.value)
        assertThat(profile.signInNeeded).isFalse()

        until("the refused key marks the profile") { device.data.sessions().current.value?.signInNeeded == true }
        val marked = checkNotNull(device.data.sessions().current.value)
        assertThat(marked.id).isEqualTo(profile.id)
        assertThat(marked.session.credential).isEqualTo(Credential.ApiKey("k4v1t4k3y", "42"))
        assertThat(synchronized(asked) { asked.count { it == "/api/Account" } }).isEqualTo(1)
    }

    @Test
    fun `a Kavita profile whose key the server takes waits for no sign-in`(): Unit = runBlocking {
        val address = kavita()

        auth.signInWithPassword(address, ServerKind.Kavita, "ann", "secret")
        until("the account is read with the profile's key") { "/api/Account" in asked }

        assertThat(device.data.sessions().current.value?.signInNeeded).isFalse()
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
