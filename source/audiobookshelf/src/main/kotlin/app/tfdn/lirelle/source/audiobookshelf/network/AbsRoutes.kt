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

/** The first call of a sign-in through the server's identity provider, from the server's address. */
internal const val OidcRoute = "auth/openid"

/** The exchange of the code the provider gave for the session, from the server's address. */
internal const val OidcCallbackRoute = "auth/openid/callback"

/** Where the browser comes back to the app from the provider, when the server allows it: Lirelle's own address. */
internal const val LirelleReturn = "lirelle://oauth"

/**
 * Where it comes back otherwise: the official app's address, which every server allows unless its administrator
 * removed it.
 */
internal const val OfficialAppReturn = "audiobookshelf://oauth"
