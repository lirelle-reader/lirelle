package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The oldest Kavita the app works with is 0.8.9: a version is compared by the dotted numbers it begins with, number
 * by number, a missing one being 0; a version that begins with no number is none, and its server is refused.
 */
class KavitaVersionTest {

    @Test
    fun `a version below the minimum is too old`() {
        listOf("0.8.8.3", "0.8.8", "0.8", "0.7.14.2", "0").forEach {
            assertWithMessage(it).that(isOlderKavita(it)).isTrue()
        }
    }

    @Test
    fun `the minimum and anything above are fine`() {
        listOf("0.8.9", "0.8.9.0", "0.8.9.1", "0.9.0", "0.9.1.4", "0.10.0", "1.0", "1").forEach {
            assertWithMessage(it).that(isOlderKavita(it)).isFalse()
        }
    }

    @Test
    fun `a version is compared by the dotted numbers it begins with`() {
        listOf("0.8.8.3-beta", "0.8.8-beta", "0.8.8+build.99", "0..9", "0.8.8.nightly", " 0.8.8 ").forEach {
            assertWithMessage(it).that(isOlderKavita(it)).isTrue()
        }
        listOf("0.8.9-beta", "0.9.1.4-nightly", "0.9.", "1 (docker)", "0.8.99999999999", " 0.9.1.4\n").forEach {
            assertWithMessage(it).that(isOlderKavita(it)).isFalse()
        }
    }

    @Test
    fun `a version that begins with no number is no version`() {
        listOf(null, "", " ", "nightly", "v0.8.1", "-1", ".8.9").forEach {
            assertWithMessage("$it").that(tellsKavitaVersion(it)).isFalse()
        }
        listOf("0", "0.8.8.3-beta", "0.9.1.4", " 1.0 ").forEach {
            assertWithMessage(it).that(tellsKavitaVersion(it)).isTrue()
        }
    }

    @Test
    fun `an account on a server that tells no version in numbers is a sign-in refused`() {
        listOf(null, "", "nightly").forEach { version ->
            val refused = assertThrows(ApiErrorException::class.java) {
                KavitaUserDto(7, "ann", kavitaVersion = version).onRecentEnoughServer()
            }

            assertWithMessage("$version").that(refused.error).isEqualTo(ApiError.SignInRefused)
        }
    }

    @Test
    fun `a version too old is told as the server said it, suffix included`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            KavitaUserDto(7, "ann", kavitaVersion = "0.8.8.3-beta").onRecentEnoughServer()
        }

        assertThat(refused.error).isEqualTo(ApiError.ServerTooOld(ServerKind.Kavita, "0.8.8.3-beta", "0.8.9"))
    }

    /** What follows the numbers is the server's to say, and may be anything: only its beginning is told. */
    @Test
    fun `a version too old is told by its numbers and the beginning of what follows them only`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            KavitaUserDto(7, "ann", kavitaVersion = " 0.8.8.3-0123456789abcdefghij" + "x".repeat(1_000_000))
                .onRecentEnoughServer()
        }

        assertThat(refused.error)
            .isEqualTo(ApiError.ServerTooOld(ServerKind.Kavita, "0.8.8.3-0123456789abcde", "0.8.9"))
    }

    /** Read without a pattern's recursion, which so many numbers would have overflowed. */
    @Test
    fun `numbers that never end are told by their beginning only`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            KavitaUserDto(7, "ann", kavitaVersion = "0" + ".0".repeat(500_000)).onRecentEnoughServer()
        }

        val told = (refused.error as ApiError.ServerTooOld).version
        assertThat(told.length).isAtMost(48)
        assertThat(told).startsWith("0.0.0.0")
    }

    @Test
    fun `an account on a server too old is refused with the version it runs and the one needed`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            KavitaUserDto(7, "ann", kavitaVersion = "0.8.8.3").onRecentEnoughServer()
        }

        assertThat(refused.error).isEqualTo(ApiError.ServerTooOld(ServerKind.Kavita, "0.8.8.3", "0.8.9"))
    }

    @Test
    fun `an account on a server recent enough is let through`() {
        val ann = KavitaUserDto(7, "ann", kavitaVersion = "0.8.9.1")

        assertThat(ann.onRecentEnoughServer()).isSameInstanceAs(ann)
    }
}
