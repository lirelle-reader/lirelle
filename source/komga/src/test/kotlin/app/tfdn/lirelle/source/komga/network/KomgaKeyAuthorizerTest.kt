package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.source.komga.testing.FakeProfileSession
import app.tfdn.lirelle.source.komga.testing.TheKey
import app.tfdn.lirelle.source.komga.testing.TheKeyId
import app.tfdn.lirelle.source.komga.testing.komgaSession
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

private const val Server = "https://bd.example.com"

/**
 * A Komga profile proves its account with its key, in `X-API-Key`, to its own server only. A key never expires:
 * one the server refuses was revoked, and the profile waits for a sign-in from then on, asking nothing.
 */
class KomgaKeyAuthorizerTest {

    private val session = FakeProfileSession(komgaSession(Server))
    private val authorizer = KomgaKeyAuthorizer(session)
    private val onServer = "$Server/api/v1/books".toHttpUrl()

    @Test
    fun `the key goes in X-API-Key to the profile's server`() = runBlocking {
        assertThat(authorizer.header(onServer)).isEqualTo(AuthHeader("X-API-Key", TheKey))
    }

    @Test
    fun `nothing goes to another server`() = runBlocking {
        assertThat(authorizer.header("https://elsewhere.example.com/api/v1/books".toHttpUrl())).isNull()
        assertThat(authorizer.header("http://bd.example.com/api/v1/books".toHttpUrl())).isNull()
        assertThat(authorizer.rejected(TheKey, "https://elsewhere.example.com/x".toHttpUrl())).isNull()
    }

    @Test
    fun `a refused key marks the profile as needing a sign-in, and the request fails without a replay`() =
        runBlocking {
            val failure = runCatching { authorizer.rejected(TheKey, onServer) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(session.signInNeeded.value).isTrue()
        }

    @Test
    fun `a profile that waits for a sign-in asks nothing in its name`() = runBlocking {
        session.signInNeeded.value = true

        val failure = runCatching { authorizer.header(onServer) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
    }

    /** The account signed in again while the refused request was on its way: its new key is not the refused one. */
    @Test
    fun `a refusal of a key the profile no longer holds replays the request with the new key, once`() =
        runBlocking {
            val renewed = Credential.ApiKey("a-new-key", "0N3W")
            session.session.value = komgaSession(Server, renewed)

            assertThat(authorizer.rejected(TheKey, onServer)).isEqualTo(AuthHeader("X-API-Key", "a-new-key"))
            assertThat(session.signInNeeded.value).isFalse()
        }

    @Test
    fun `the profile keeps its key when it is refused, the mark says it and nothing is removed`() = runBlocking {
        runCatching { authorizer.rejected(TheKey, onServer) }

        assertThat(session.session.value.credential).isEqualTo(Credential.ApiKey(TheKey, TheKeyId))
        assertThat(session.signInNeeded.value).isTrue()
    }
}
