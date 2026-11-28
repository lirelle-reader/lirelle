package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.source.audiobookshelf.testing.AccessExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.RefreshExpiry
import app.tfdn.lirelle.source.audiobookshelf.testing.SignedInAt
import app.tfdn.lirelle.source.audiobookshelf.testing.TheAccessToken
import app.tfdn.lirelle.source.audiobookshelf.testing.TheRefreshToken
import app.tfdn.lirelle.source.audiobookshelf.testing.madeUpJwt
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Duration
import java.time.Instant
import org.junit.Test

/**
 * Audiobookshelf's tokens are JWTs: when each expires is read from them, without checking their signature — the
 * server checks it, the app only needs to know when to renew. On the device's clock: the lifetime the token states
 * (`exp` − `iat`) from when it was received, so that a clock ahead of or behind the server's does not matter; its
 * `exp` alone when it states no `iat`. An access token whose expiry cannot be read lasts until the server refuses
 * it; a refresh token, seven days from its receipt, the shortest any version gave.
 */
class AbsJwtTest {

    /** When the device received the tokens: two hours after the server made them, by the device's clock. */
    private val received: Instant = Instant.parse("2026-11-27T12:00:00Z")

    @Test
    fun `the expiry is the token's lifetime from its receipt, on the device's clock`() {
        assertThat(AbsJwt.expiry(TheAccessToken, received)).isEqualTo(received.plus(Duration.ofHours(1)))
        assertThat(AbsJwt.expiry(TheRefreshToken, received)).isEqualTo(received.plus(Duration.ofDays(30)))
        assertThat(AbsJwt.accessExpiry(TheAccessToken, SignedInAt)).isEqualTo(AccessExpiry)
        assertThat(AbsJwt.refreshExpiry(TheRefreshToken, SignedInAt)).isEqualTo(RefreshExpiry)
    }

    @Test
    fun `a clock behind the server's does not matter either`() {
        val behind = SignedInAt.minus(Duration.ofHours(1))

        assertThat(AbsJwt.accessExpiry(TheAccessToken, behind)).isEqualTo(behind.plus(Duration.ofHours(1)))
    }

    @Test
    fun `a token without iat expires at its exp`() {
        val token = madeUpJwt("""{"exp":${AccessExpiry.epochSecond}}""")

        assertThat(AbsJwt.expiry(token, received)).isEqualTo(AccessExpiry)
        assertThat(AbsJwt.accessExpiry(token, received)).isEqualTo(AccessExpiry)
    }

    /** No lifetime can be read from it: its exp is all it says. */
    @Test
    fun `a token whose exp is not after its iat expires at its exp`() {
        val token = madeUpJwt("""{"iat":${AccessExpiry.epochSecond},"exp":${SignedInAt.epochSecond}}""")

        assertThat(AbsJwt.expiry(token, received)).isEqualTo(SignedInAt)
    }

    /** Base64url without padding, whatever the payload's length: each of the three remainders. */
    @Test
    fun `a payload of any length is read`() {
        listOf("", "a", "ab", "abc").forEach { extra ->
            val token = madeUpJwt("""{"exp":${SignedInAt.epochSecond},"x":"$extra"}""")

            assertWithMessage(extra).that(AbsJwt.expiry(token, received)).isEqualTo(SignedInAt)
        }
    }

    @Test
    fun `claims with a fraction of a second are read to the second`() {
        assertThat(AbsJwt.expiry(madeUpJwt("""{"exp":${SignedInAt.epochSecond}.75}"""), received))
            .isEqualTo(SignedInAt)
        val withIat = madeUpJwt("""{"iat":${SignedInAt.epochSecond}.5,"exp":${AccessExpiry.epochSecond}.75}""")
        assertThat(AbsJwt.expiry(withIat, received)).isEqualTo(received.plus(Duration.ofHours(1)))
    }

    /** Never "expiring now", which would renew it before every request: the server's 401 renews it. */
    @Test
    fun `an access token without exp lasts until the server refuses it, a refresh token seven days`() {
        val token = madeUpJwt("""{"userId":"u","type":"access","iat":${SignedInAt.epochSecond}}""")

        assertThat(AbsJwt.expiry(token, received)).isNull()
        assertThat(AbsJwt.accessExpiry(token, received)).isEqualTo(AbsJwt.UntilRefused)
        assertThat(AbsJwt.refreshExpiry(token, received)).isEqualTo(received.plus(Duration.ofDays(7)))
    }

    /** The profile keeps it as text, read back as it was written. */
    @Test
    fun `lasting until refused survives being kept as text`() {
        assertThat(Instant.parse(AbsJwt.UntilRefused.toString())).isEqualTo(AbsJwt.UntilRefused)
        assertThat(AbsJwt.UntilRefused.plus(Duration.ofDays(365))).isGreaterThan(AbsJwt.UntilRefused)
    }

    @Test
    fun `garbage has no expiry`() {
        val garbage = listOf(
            "",
            "not-a-jwt",
            "a.b",
            "a.%%%.c",
            madeUpJwt("not json"),
            madeUpJwt("[1,2]"),
            madeUpJwt("""{"exp":"tomorrow"}"""),
            madeUpJwt("""{"exp":null}"""),
            madeUpJwt("""{"exp":{"at":1}}"""),
            madeUpJwt("""{"exp":1e300}"""),
            madeUpJwt("""{"iat":${SignedInAt.epochSecond}}"""),
        )
        garbage.forEach { token ->
            assertWithMessage(token).that(AbsJwt.expiry(token, received)).isNull()
            assertWithMessage(token).that(AbsJwt.accessExpiry(token, received)).isEqualTo(AbsJwt.UntilRefused)
        }
    }

    /** An iat that cannot be read leaves the exp: the lifetime cannot be told. */
    @Test
    fun `an unreadable iat leaves the exp`() {
        val token = madeUpJwt("""{"iat":"yesterday","exp":${AccessExpiry.epochSecond}}""")

        assertThat(AbsJwt.expiry(token, received)).isEqualTo(AccessExpiry)
    }
}
