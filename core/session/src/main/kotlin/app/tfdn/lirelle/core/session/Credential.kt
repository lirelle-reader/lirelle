package app.tfdn.lirelle.core.session

/**
 * What a profile's server takes as proof that the account signed in on this device. Its sort is the kind's
 * ([Profile.kind]): one kind of server never holds another's.
 */
sealed interface Credential {

    /**
     * Whether [other] is this same sign-in: an answer of the server about [other] — renewed, refused — is then
     * about the one held now. Not equality: a sign-in keeps its name while what it holds changes.
     */
    fun isSameSignIn(other: Credential): Boolean

    /**
     * A session opened by signing in, renewed with its refresh token: BookOrbit's.
     *
     * @property sessionId the server's name for the session.
     */
    data class SessionTokens(val sessionId: Long, val tokens: Tokens) : Credential {
        /**
         * The refresh token names the session: the access token changes within one, and a sign-in draws a new
         * refresh token.
         */
        override fun isSameSignIn(other: Credential): Boolean =
            other is SessionTokens && other.tokens.refreshToken == tokens.refreshToken
    }

    /**
     * A key of the account, which never expires and lasts until it is revoked: Komga's, and Kavita's.
     *
     * @property keyId the server's name for the key, by which it is revoked.
     * @property ownedByApp the app made this key, and removes it from the server when the profile goes. False: the
     *   user pasted a key of their own, which the app only forgets.
     */
    data class ApiKey(val key: String, val keyId: String, val ownedByApp: Boolean = true) : Credential {
        override fun isSameSignIn(other: Credential): Boolean = other is ApiKey && other.keyId == keyId

        /** Never prints the key: this text ends up in logs and in failed assertions. */
        override fun toString(): String = "ApiKey(key=<redacted>, keyId=$keyId)"
    }
}
