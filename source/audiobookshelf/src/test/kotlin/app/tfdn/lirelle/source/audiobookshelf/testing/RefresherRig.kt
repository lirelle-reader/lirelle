package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.source.audiobookshelf.network.AbsTokenRefresher
import app.tfdn.lirelle.source.audiobookshelf.network.absRefreshApi
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** How long a held answer waits for the test to let it go before it fails the test instead. */
private const val HeldAnswerSeconds = 60L

/**
 * An Audiobookshelf profile, signed in on [server], and its authorizer as the app makes it ([absRefreshApi]), on a
 * device whose clock says [clock] — [SignedInAt] unless the test moves it. [attempts] counts the renewals the
 * authorizer made, even those whose connection could not be made.
 */
internal class RefresherRig(val server: MockWebServer) {
    val profile = FakeProfileSession(absSession(server.serverUrl))
    val attempts = AtomicInteger()

    /** What the device's clock says. */
    @Volatile
    var clock: Instant = SignedInAt
    private val counting = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> attempts.incrementAndGet(); chain.proceed(chain.request()) })
        .build()
    val refresher = AbsTokenRefresher(profile, absRefreshApi(counting)) { clock }

    /** Signs in with an access token expiring at [accessExpiresAt]: `access-1`, and the refresh token `refresh-1`. */
    fun signIn(accessExpiresAt: Instant = AccessExpiry) {
        profile.session.value = absSession(server.serverUrl, absTokens(accessExpiresAt))
    }

    /** Signs in as the same account signing in again: another sign-in, another pair. */
    fun signInAgain(): Tokens {
        val again = absTokens(access = "access-again", refresh = "refresh-again")
        profile.session.value = absSession(server.serverUrl, again, signInId = "another-sign-in")
        return again
    }

    /**
     * The server holds [response] back until the returned latch is opened: the test changes the profile under the
     * renewal, then lets the answer come. No delay is raced.
     */
    fun held(response: MockResponse.Builder): CountDownLatch {
        val answer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(answer.await(HeldAnswerSeconds, TimeUnit.SECONDS)) { "The test never let the answer go" }
                return response.build()
            }
        }
        return answer
    }
}

/** The server reads the request, so it may have rotated the token, and the answer never comes back. */
fun lostAnswer(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

/** The answer to a renewal, as JSON. */
fun jsonAnswer(body: String, code: Int = 200): MockResponse.Builder =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
