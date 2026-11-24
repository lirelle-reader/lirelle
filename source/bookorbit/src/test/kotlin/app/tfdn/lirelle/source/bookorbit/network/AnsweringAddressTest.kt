package app.tfdn.lirelle.source.bookorbit.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class AnsweringAddressTest {

    private fun answered(base: String) = "$base/api/v1/server-identity".toHttpUrl()

    @Test
    fun `an address that answered directly is kept as typed`() {
        assertThat(answeringAddress("https://books.example.com", answered("https://books.example.com")))
            .isEqualTo("https://books.example.com")
    }

    @Test
    fun `a cleartext address redirected to https becomes the https address`() {
        assertThat(answeringAddress("http://books.example.com", answered("https://books.example.com")))
            .isEqualTo("https://books.example.com")
    }

    @Test
    fun `an https address redirected to another https address becomes that address`() {
        assertThat(answeringAddress("https://books.example.com", answered("https://other.example.com/orbit")))
            .isEqualTo("https://other.example.com/orbit")
    }

    @Test
    fun `a cleartext address redirected to another cleartext address becomes that address`() {
        assertThat(answeringAddress("http://books.lan", answered("http://books.lan:8080")))
            .isEqualTo("http://books.lan:8080")
    }

    // The cleartext warning is only shown for an address typed with http: a redirect must
    // not move the user to cleartext behind their back.
    @Test
    fun `an https address redirected to cleartext is refused`() {
        assertThat(answeringAddress("https://books.example.com", answered("http://books.example.com")))
            .isNull()
        assertThat(answeringAddress("https://books.example.com", answered("http://other.example.com")))
            .isNull()
    }

    @Test
    fun `an answer from somewhere that is not an identity route is refused`() {
        assertThat(answeringAddress("https://books.example.com", "https://books.example.com/login".toHttpUrl()))
            .isNull()
    }
}
