package app.tfdn.lirelle.core.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) helpers. [randomToken] also serves for the OIDC nonce. */
object Pkce {
    private const val TokenBytes = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun randomToken(): String = encoder.encodeToString(ByteArray(TokenBytes).also(random::nextBytes))

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
