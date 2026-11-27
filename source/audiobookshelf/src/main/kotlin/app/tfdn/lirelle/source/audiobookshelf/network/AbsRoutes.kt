package app.tfdn.lirelle.source.audiobookshelf.network

/** What anybody may ask, from the server's address: the server's name, version and ways of signing in. */
internal const val StatusRoute = "status"

/** A sign-in with a name and a password, from the server's address. */
internal const val LoginRoute = "login"

/** The end of a sign-in's session, from the server's address. */
internal const val LogoutRoute = "logout"

/** What asks a sign-in to answer with the tokens in its body, rather than the refresh token in a cookie. */
internal const val ReturnTokensHeader = "x-return-tokens"

/** The header a refresh token goes in, to renew the session or to close it. */
internal const val RefreshTokenHeader = "x-refresh-token"
