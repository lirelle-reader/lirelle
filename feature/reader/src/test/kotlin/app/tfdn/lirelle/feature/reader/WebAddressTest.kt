package app.tfdn.lirelle.feature.reader

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class WebAddressTest {

    @Test
    fun `web addresses go through`() {
        assertThat(webAddress("https://example.org/a?b=c#d")).isEqualTo("https://example.org/a?b=c#d")
        assertThat(webAddress("http://example.org")).isEqualTo("http://example.org")
        assertThat(webAddress("HTTPS://Example.org/")).isEqualTo("HTTPS://Example.org/")
    }

    @Test
    fun `every other scheme is refused`() {
        listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///data/data/app/files/secret",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>alert(1)</script>",
            "tel:+33123456789",
            "mailto:someone@example.org",
            "market://details?id=x",
            "ftp://example.org/file",
            "httpx://example.org",
        ).forEach { assertWithMessage(it).that(webAddress(it)).isNull() }
    }

    @Test
    fun `what is not a well-formed address with a host is refused`() {
        listOf(
            "",
            "chapter2.xhtml",
            "//example.org/a",
            "http:///nowhere",
            "https:",
            "https://exa mple.org",
            " https://example.org",
            "https://example.org/\u0000",
            "java\nscript:alert(1)",
        ).forEach { assertWithMessage(it).that(webAddress(it)).isNull() }
    }

    @Test
    fun `an address that hides its host behind a user name is refused`() {
        assertThat(webAddress("https://example.org@elsewhere.test/")).isNull()
        assertThat(webAddress("https://user:password@example.org/")).isNull()
    }
}
