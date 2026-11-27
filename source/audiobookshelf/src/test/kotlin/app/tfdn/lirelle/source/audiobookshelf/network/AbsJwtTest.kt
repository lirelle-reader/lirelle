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
 * Audiobookshelf's tokens are JWTs: when each expires is read from its `exp`, without checking its signature — the
 * server checks it, the app only needs to know when to renew. A token whose expiry cannot be read is renewed
 * before its first use (an access token) or taken to last seven days, the shortest any version gave (a refresh
 * token).
 */
class AbsJwtTest {

    private val now: Instant = Instant.parse("2026-11-27T12:00:00Z")

    @Test
    fun `the expiry is read from the token's exp`() {
        assertThat(AbsJwt.expiry(TheAccessToken)).isEqualTo(AccessExpiry)
        assertThat(AbsJwt.expiry(TheRefreshToken)).isEqualTo(RefreshExpiry)
        assertThat(AbsJwt.accessExpiry(TheAccessToken, now)).isEqualTo(AccessExpiry)
        assertThat(AbsJwt.refreshExpiry(TheRefreshToken, now)).isEqualTo(RefreshExpiry)
    }

    /** Base64url without padding, whatever the payload's length: each of the three remainders. */
    @Test
    fun `a payload of any length is read`() {
        listOf("", "a", "ab", "abc").forEach { extra ->
            val token = madeUpJwt("""{"exp":${SignedInAt.epochSecond},"x":"$extra"}""")

            assertWithMessage(extra).that(AbsJwt.expiry(token)).isEqualTo(SignedInAt)
        }
    }

    @Test
    fun `an exp with a fraction of a second is read to the second`() {
        assertThat(AbsJwt.expiry(madeUpJwt("""{"exp":${SignedInAt.epochSecond}.75}"""))).isEqualTo(SignedInAt)
    }

    @Test
    fun `a token without exp expires at once, or lasts seven days if it is a refresh token`() {
        val token = madeUpJwt("""{"userId":"u","type":"access","iat":${SignedInAt.epochSecond}}""")

        assertThat(AbsJwt.expiry(token)).isNull()
        assertThat(AbsJwt.accessExpiry(token, now)).isEqualTo(now)
        assertThat(AbsJwt.refreshExpiry(token, now)).isEqualTo(now.plus(Duration.ofDays(7)))
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
        )
        garbage.forEach { token ->
            assertWithMessage(token).that(AbsJwt.expiry(token)).isNull()
            assertWithMessage(token).that(AbsJwt.accessExpiry(token, now)).isEqualTo(now)
        }
    }
}
