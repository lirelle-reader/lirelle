package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `adds https when no scheme is given`() {
        assertThat(ServerAddress.parse("books.example.com"))
            .isEqualTo(ServerAddress.Valid("https://books.example.com", isCleartext = false))
    }

    @Test
    fun `keeps a port given without a scheme`() {
        assertThat(ServerAddress.parse("books.example.com:8443"))
            .isEqualTo(ServerAddress.Valid("https://books.example.com:8443", isCleartext = false))
    }

    @Test
    fun `removes surrounding spaces and the trailing slash`() {
        assertThat(ServerAddress.parse("  https://books.example.com/  "))
            .isEqualTo(ServerAddress.Valid("https://books.example.com", isCleartext = false))
    }

    @Test
    fun `keeps a sub-path`() {
        assertThat(ServerAddress.parse("https://example.com/books/"))
            .isEqualTo(ServerAddress.Valid("https://example.com/books", isCleartext = false))
    }

    @Test
    fun `drops query and fragment`() {
        assertThat(ServerAddress.parse("https://books.example.com/?a=1#top"))
            .isEqualTo(ServerAddress.Valid("https://books.example.com", isCleartext = false))
    }

    @Test
    fun `accepts explicit http and flags it as cleartext`() {
        assertThat(ServerAddress.parse("http://homeserver:3000"))
            .isEqualTo(ServerAddress.Valid("http://homeserver:3000", isCleartext = true))
    }

    @Test
    fun `rejects other schemes`() {
        assertThat(ServerAddress.parse("ftp://books.example.com")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("bookorbit://oauth2-callback")).isEqualTo(ServerAddress.Invalid)
    }

    @Test
    fun `rejects blank and malformed input`() {
        assertThat(ServerAddress.parse("")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("   ")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("http://")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("books example com")).isEqualTo(ServerAddress.Invalid)
    }

    @Test
    fun `rejects an address carrying a user name or a password`() {
        assertThat(ServerAddress.parse("https://reader:secret@books.example.com")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("https://reader@books.example.com")).isEqualTo(ServerAddress.Invalid)
        assertThat(ServerAddress.parse("reader:secret@books.example.com")).isEqualTo(ServerAddress.Invalid)
    }

    @Test
    fun `only a scheme at the start counts as one`() {
        assertThat(ServerAddress.parse("books.example.com/path?next=http://x"))
            .isEqualTo(ServerAddress.Valid("https://books.example.com/path", isCleartext = false))
        assertThat(ServerAddress.parse("HTTP://books.example.com"))
            .isEqualTo(ServerAddress.Valid("http://books.example.com", isCleartext = true))
    }
}
