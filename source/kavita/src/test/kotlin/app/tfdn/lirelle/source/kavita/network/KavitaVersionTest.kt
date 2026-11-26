package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The oldest Kavita the app works with is 0.8.9: a version is compared number by number, a missing one being 0,
 * and a version that is not dotted numbers is let through.
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
    fun `a version that is not dotted numbers is let through`() {
        listOf("nightly", "", "0.8.8-beta", "0..8", "v0.8.1", "0.8.99999999999").forEach {
            assertWithMessage(it).that(isOlderKavita(it)).isFalse()
        }
    }

    @Test
    fun `an account on a server too old is refused with the version it runs and the one needed`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            KavitaUserDto(7, "ann", kavitaVersion = "0.8.8.3").onRecentEnoughServer()
        }

        assertThat(refused.error).isEqualTo(ApiError.ServerTooOld("0.8.8.3", "0.8.9"))
    }

    @Test
    fun `an account on a server recent enough is let through`() {
        val ann = KavitaUserDto(7, "ann", kavitaVersion = "0.8.9.1")

        assertThat(ann.onRecentEnoughServer()).isSameInstanceAs(ann)
    }
}
