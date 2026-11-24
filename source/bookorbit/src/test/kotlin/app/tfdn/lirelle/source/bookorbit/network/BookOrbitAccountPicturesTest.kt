package app.tfdn.lirelle.source.bookorbit.network

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

private const val Server = "https://books.example:8443"

/** BookOrbit gives the account's picture as a path on its server: only such a path is an address. */
class BookOrbitAccountPicturesTest {

    @Test
    fun `the picture's path is put after the profile's address`() {
        assertThat(BookOrbitAccountPictures.pictureUrl(Server, "/api/v1/users/1/avatar?v=1"))
            .isEqualTo("$Server/api/v1/users/1/avatar?v=1".toHttpUrl())
        assertThat(BookOrbitAccountPictures.pictureUrl("$Server/", "/picture/1"))
            .isEqualTo("$Server/picture/1".toHttpUrl())
    }

    @Test
    fun `what is not a path on the profile's own server is no address`() {
        val other = "elsewhere.example:8443"
        val refused = listOf(
            "https://$other/api/v1/users/1/avatar?v=1",
            "//$other/api/v1/users/1/avatar?v=1",
            "/api/v1/users/1/../../../admin?v=1",
            "/api/v1/users/%2e%2e/avatar",
            "/api/v1/users/%2E./avatar",
            "\\\\$other/avatar",
            "/\\$other/avatar",
            "api/v1/users/1/avatar",
            "@$other/avatar",
            "",
        )

        assertThat(refused.mapNotNull { BookOrbitAccountPictures.pictureUrl(Server, it) }).isEmpty()
    }

    @Test
    fun `a profile's address that does not read gives no address`() {
        assertThat(BookOrbitAccountPictures.pictureUrl("not an address", "/picture/1")).isNull()
    }
}
