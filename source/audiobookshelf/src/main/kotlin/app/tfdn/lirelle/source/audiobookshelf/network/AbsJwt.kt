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
 * When Audiobookshelf's tokens expire, read from them: they are JWTs, whose payload's `exp` is the second they
 * expire at. Not checked — the server checks its signature on every call; the app only needs to know when to renew.
 * Neither the token nor anything of it is told anywhere.
 */
internal object AbsJwt {

    /** When [token] expires; null when it is no JWT, or says no `exp` in seconds the app can read. */
    fun expiry(token: String): Instant? {
        val payload = token.split('.').takeIf { it.size == JwtParts }?.let { decoded(it[PayloadPart]) }
        val exp = payload?.let(::claims)?.get("exp") as? JsonPrimitive
        if (exp == null || exp.isString) return null
        val seconds = exp.longOrNull ?: exp.doubleOrNull?.takeIf { it.isFinite() && it in LongSeconds }?.toLong()
        return seconds?.let(::instantOrNull)
    }

    /** When the access token [token] expires: at once when it does not say, so that it is renewed before use. */
    fun accessExpiry(token: String, now: Instant): Instant = expiry(token) ?: now

    /** When the refresh token [token] expires: seven days from [now] when it does not say. */
    fun refreshExpiry(token: String, now: Instant): Instant = expiry(token) ?: now.plus(UnknownRefreshLifetime)

    /** The claims of a JWT's [payload]; null when it is no JSON object. */
    private fun claims(payload: String): JsonObject? = try {
        AbsJson.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
        null
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

    /** The seconds a double may hold and still be a [Long]. */
    private val LongSeconds = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
}
