package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AuthHeader
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.source.kavita.testing.FakeProfileSession
import app.tfdn.lirelle.source.kavita.testing.TheKey
import app.tfdn.lirelle.source.kavita.testing.TheKeyId
import app.tfdn.lirelle.source.kavita.testing.kavitaSession
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

private const val Server = "https://kavita.example.com"

/**
 * A Kavita profile proves its account with its key, in `x-api-key`, to its own server only. A key the server
 * refuses was deleted or has expired: the profile waits for a sign-in from then on, asking nothing. The routes that
 * take the key in their address are given it the same way.
 */
class KavitaKeyAuthorizerTest {

    private val session = FakeProfileSession(kavitaSession(Server))
    private val authorizer = KavitaKeyAuthorizer(session)
    private val onServer = "$Server/api/Account".toHttpUrl()

    @Test
    fun `the key goes in x-api-key to the profile's server`() = runBlocking {
        assertThat(authorizer.header(onServer)).isEqualTo(AuthHeader("x-api-key", TheKey))
    }

    @Test
    fun `nothing goes to another server`() = runBlocking {
        assertThat(authorizer.header("https://elsewhere.example.com/api/Account".toHttpUrl())).isNull()
        assertThat(authorizer.header("http://kavita.example.com/api/Account".toHttpUrl())).isNull()
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
            val renewed = Credential.ApiKey("a-new-key", "43")
            session.session.value = kavitaSession(Server, renewed)

            assertThat(authorizer.rejected(TheKey, onServer)).isEqualTo(AuthHeader("x-api-key", "a-new-key"))
            assertThat(session.signInNeeded.value).isFalse()
        }

    @Test
    fun `the profile keeps its key when it is refused, the mark says it and nothing is removed`() = runBlocking {
        runCatching { authorizer.rejected(TheKey, onServer) }

        assertThat(session.session.value.credential).isEqualTo(Credential.ApiKey(TheKey, TheKeyId))
        assertThat(session.signInNeeded.value).isTrue()
    }

    @Test
    fun `a key the user pasted is refused as the device's own, and stays the user's`() = runBlocking {
        val pasted = Credential.ApiKey("p4st3d", keyId = "50", ownedByApp = false)
        session.session.value = kavitaSession(Server, pasted)

        runCatching { authorizer.rejected("p4st3d", onServer) }

        assertThat(session.session.value.credential).isEqualTo(pasted)
        assertThat(session.signInNeeded.value).isTrue()
    }

    @Test
    fun `an address of the profile's server is given the key as its apiKey parameter`() {
        val cover = "$Server/api/Image/series-cover?seriesId=12".toHttpUrl()

        assertThat(authorizer.withKey(cover))
            .isEqualTo("$Server/api/Image/series-cover?seriesId=12&apiKey=$TheKey".toHttpUrl())
    }

    @Test
    fun `an address elsewhere is given no key, nor is any while the profile waits for a sign-in`() {
        assertThat(authorizer.withKey("https://elsewhere.example.com/api/Image/series-cover".toHttpUrl())).isNull()
        assertThat(authorizer.withKey("http://kavita.example.com/api/Image/series-cover".toHttpUrl())).isNull()

        session.signInNeeded.value = true

        assertThat(authorizer.withKey("$Server/api/Image/series-cover?seriesId=12".toHttpUrl())).isNull()
    }

    @Test
    fun `an address on another port of the server's host is given no key`() {
        assertThat(authorizer.withKey("https://kavita.example.com:8443/api/Image/series-cover".toHttpUrl())).isNull()

        val onPort = KavitaKeyAuthorizer(FakeProfileSession(kavitaSession("https://kavita.example.com:5000")))
        assertThat(onPort.withKey("https://kavita.example.com/api/Image/series-cover".toHttpUrl())).isNull()
        assertThat(onPort.withKey("https://kavita.example.com:5000/api/Image/series-cover".toHttpUrl()))
            .isEqualTo("https://kavita.example.com:5000/api/Image/series-cover?apiKey=$TheKey".toHttpUrl())
    }

    /** An address that came with a key — another's, an old one — leaves with the profile's, and only it. */
    @Test
    fun `a key already in the address is replaced by the profile's`() {
        val withAnother = "$Server/api/Image/series-cover?apiKey=another&seriesId=12&apiKey=again".toHttpUrl()

        val keyed = checkNotNull(authorizer.withKey(withAnother))

        assertThat(keyed.queryParameterValues("apiKey")).containsExactly(TheKey)
        assertThat(keyed.queryParameter("seriesId")).isEqualTo("12")
    }

    @Test
    fun `a session that holds no key gives none`() {
        val tokens = Tokens("access", Instant.EPOCH, "refresh", Instant.EPOCH)
        session.session.value = kavitaSession(Server).copy(credential = Credential.SessionTokens(sessionId = 1, tokens))

        assertThat(authorizer.withKey("$Server/api/Image/series-cover?seriesId=12".toHttpUrl())).isNull()
    }

    @Test
    fun `under a base path, an address of the server is given the key`() {
        val under = KavitaKeyAuthorizer(FakeProfileSession(kavitaSession("https://h.example.com/kavita")))

        assertThat(under.withKey("https://h.example.com/kavita/api/Image/series-cover?seriesId=12".toHttpUrl()))
            .isEqualTo("https://h.example.com/kavita/api/Image/series-cover?seriesId=12&apiKey=$TheKey".toHttpUrl())
        assertThat(under.withKey("https://elsewhere.example.com/kavita/api/Image/series-cover".toHttpUrl())).isNull()
    }

    @Test
    fun `a request for an image of the profile's server goes with the key in its address`() {
        val cover = "$Server/api/Image/series-cover?seriesId=7".toHttpUrl()

        assertThat(authorizer.address(cover))
            .isEqualTo("$Server/api/Image/series-cover?seriesId=7&apiKey=$TheKey".toHttpUrl())
    }

    @Test
    fun `a request for any other route goes to its own address`() {
        val list = "$Server/api/Series/all-v2?PageNumber=1&PageSize=20".toHttpUrl()

        assertThat(authorizer.address(list)).isEqualTo(list)
    }

    @Test
    fun `a request for an image of another server goes without the key`() {
        val elsewhere = "https://elsewhere.example.com/api/Image/series-cover?seriesId=7".toHttpUrl()

        assertThat(authorizer.address(elsewhere)).isEqualTo(elsewhere)
    }

    /** The header then fails the request, as before: the address alone sends nothing. */
    @Test
    fun `a request for an image while the profile waits for a sign-in goes without the key`() {
        session.signInNeeded.value = true
        val cover = "$Server/api/Image/series-cover?seriesId=7".toHttpUrl()

        assertThat(authorizer.address(cover)).isEqualTo(cover)
        assertThat(runCatching { runBlocking { authorizer.header(cover) } }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `under a base path, a request for an image goes with the key in its address`() {
        val under = KavitaKeyAuthorizer(FakeProfileSession(kavitaSession("https://h.example.com/kavita")))
        val cover = "https://h.example.com/kavita/api/Image/chapter-cover?chapterId=3".toHttpUrl()

        assertThat(under.address(cover))
            .isEqualTo("https://h.example.com/kavita/api/Image/chapter-cover?chapterId=3&apiKey=$TheKey".toHttpUrl())
    }

    @Test
    fun `a page and a PDF file are asked with the key in their address`() {
        val page = "$Server/api/Reader/image?chapterId=44&page=0".toHttpUrl()
        val pdf = "$Server/api/Reader/pdf?chapterId=1355".toHttpUrl()

        assertThat(authorizer.address(page)).isEqualTo("$page&apiKey=$TheKey".toHttpUrl())
        assertThat(authorizer.address(pdf)).isEqualTo("$pdf&apiKey=$TheKey".toHttpUrl())
    }

    @Test
    fun `the other reading routes go to their own address`() {
        val progress = "$Server/api/Reader/progress".toHttpUrl()
        val info = "$Server/api/Reader/chapter-info?chapterId=44".toHttpUrl()
        val imageLike = "$Server/api/Reader/image-something?chapterId=44".toHttpUrl()

        assertThat(authorizer.address(progress)).isEqualTo(progress)
        assertThat(authorizer.address(info)).isEqualTo(info)
        assertThat(authorizer.address(imageLike)).isEqualTo(imageLike)
    }

    @Test
    fun `a page of another server goes without the key`() {
        val elsewhere = "https://elsewhere.example.com/api/Reader/image?chapterId=44&page=0".toHttpUrl()

        assertThat(authorizer.address(elsewhere)).isEqualTo(elsewhere)
    }
}
