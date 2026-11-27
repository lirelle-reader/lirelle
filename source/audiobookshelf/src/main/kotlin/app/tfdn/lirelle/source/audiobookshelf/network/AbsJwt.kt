package app.tfdn.lirelle.source.audiobookshelf.network

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** How long a refresh token whose expiry cannot be read is taken to last: the shortest any version gave (2.26). */
private val UnknownRefreshLifetime: Duration = Duration.ofDays(7)

/** The payload's place among a JWT's three parts: `header.payload.signature`. */
private const val PayloadPart = 1
private const val JwtParts = 3

/**
 * When Audiobookshelf's tokens expire, read from them: they are JWTs, whose payload's `iat` is the second they were
 * made at and `exp` the second they expire at, both on the server's clock. Not checked — the server checks its
 * signature on every call; the app only needs to know when to renew. Neither the token nor anything of it is told
 * anywhere.
 *
 * On the device's clock, which may be ahead of the server's or behind it: a token lasts the lifetime it states,
 * `exp` − `iat`, from when the device received it. Its `exp` is taken as it is only when it states no `iat`.
 */
internal object AbsJwt {

    /**
     * When an access token whose expiry cannot be read is taken to expire: never before the server refuses it,
     * whose 401 renews it. Far, yet an instant every clock can still count from, and that the profile keeps as text.
     */
    val UntilRefused: Instant = Instant.parse("9999-12-31T23:59:59Z")

    /**
     * When [token], received at [receivedAt] by the device's clock, expires on that clock; null when it is no JWT,
     * or says no `exp` in seconds the app can read.
     */
    fun expiry(token: String, receivedAt: Instant): Instant? {
        val claims = token.split('.').takeIf { it.size == JwtParts }?.let { decoded(it[PayloadPart]) }?.let(::claims)
        val exp = claims?.seconds("exp") ?: return null
        // A lifetime that is none — an exp not after the iat, or too far apart to count — is not read.
        val lifetime = claims.seconds("iat")?.let { iat -> lifetimeOrNull(exp, iat) }?.takeIf { it > 0 }
        return lifetime?.let { receivedAt.plusSecondsOrNull(it) } ?: instantOrNull(exp)
    }

    /** When the access token [token] expires: when the server refuses it, if it does not say ([UntilRefused]). */
    fun accessExpiry(token: String, receivedAt: Instant): Instant = expiry(token, receivedAt) ?: UntilRefused

    /** When the refresh token [token] expires: seven days from [receivedAt] when it does not say. */
    fun refreshExpiry(token: String, receivedAt: Instant): Instant =
        expiry(token, receivedAt) ?: receivedAt.plus(UnknownRefreshLifetime)

    /** The claims of a JWT's [payload]; null when it is no JSON object. */
    private fun claims(payload: String): JsonObject? = try {
        AbsJson.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    /** The claim [name] as whole seconds; null when it is missing, or no number a [Long] can hold. */
    private fun JsonObject.seconds(name: String): Long? {
        val claim = get(name) as? JsonPrimitive
        if (claim == null || claim.isString) return null
        return claim.longOrNull ?: claim.doubleOrNull?.takeIf { it.isFinite() && it in LongSeconds }?.toLong()
    }

    /** Base64url, without padding as JWTs write it, or with; null when it is not. */
    private fun decoded(part: String): String? = try {
        String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun instantOrNull(seconds: Long): Instant? = try {
        Instant.ofEpochSecond(seconds)
    } catch (_: DateTimeException) {
        null
    }

    private fun lifetimeOrNull(exp: Long, iat: Long): Long? = try {
        Math.subtractExact(exp, iat)
    } catch (_: ArithmeticException) {
        null
    }

    private fun Instant.plusSecondsOrNull(seconds: Long): Instant? = try {
        plusSeconds(seconds)
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /** The seconds a double may hold and still be a [Long]. */
    private val LongSeconds = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
}
