package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.session.ServerKind
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The oldest Audiobookshelf the app works with is 2.26.0, the first with access and refresh tokens: a version is
 * compared by the dotted numbers it begins with, number by number, a missing one being 0; a version that begins
 * with no number is refused as a server too old.
 */
class AbsVersionTest {

    private fun tooOld(version: String) = ApiError.ServerTooOld(ServerKind.Audiobookshelf, version, "2.26")

    @Test
    fun `versions are compared number by number`() {
        assertThat(AbsVersion.parse("2.38.0")!!).isGreaterThan(AbsVersion.parse("2.26.3")!!)
        assertThat(AbsVersion.parse("2.9.0")!!).isLessThan(AbsVersion.parse("2.10.0")!!)
        assertThat(AbsVersion.parse("2.26")!!).isEquivalentAccordingToCompareTo(AbsVersion.parse("2.26.0")!!)
    }

    @Test
    fun `a version below the minimum is too old`() {
        listOf("2.25.1", "2.25", "2.6.0", "1.9.9", "2", "2.25.9-beta").forEach { version ->
            val refused = assertThrows(ApiErrorException::class.java) { requireRecentAbs(version) }

            assertWithMessage(version).that(refused.error).isEqualTo(tooOld(version))
        }
    }

    @Test
    fun `the minimum and anything above are fine`() {
        listOf("2.26.0", "2.26", "2.26.3", "2.38.0", "3.0.0", "2.38.0-beta", " 2.38.0\n", "2.99999999999").forEach {
            requireRecentAbs(it)
        }
    }

    @Test
    fun `a version without numbers is refused as too old`() {
        listOf("beta", "v2.38.0", "").forEach { version ->
            val refused = assertThrows(ApiErrorException::class.java) { requireRecentAbs(version) }

            assertWithMessage(version).that(refused.error).isEqualTo(tooOld(version))
        }
        assertThat(AbsVersion.parse("beta")).isNull()
    }

    @Test
    fun `no version at all is refused as too old`() {
        val refused = assertThrows(ApiErrorException::class.java) { requireRecentAbs(null) }

        assertThat(refused.error).isEqualTo(tooOld(""))
    }

    /** Told to the user: the numbers and the beginning of what follows, never all a server may have sent. */
    @Test
    fun `the version told is cut short`() {
        val refused = assertThrows(ApiErrorException::class.java) {
            requireRecentAbs("2.25.1-0123456789abcdefghij" + "x".repeat(100_000))
        }

        assertThat(refused.error).isEqualTo(tooOld("2.25.1-0123456789abcde"))
    }
}
