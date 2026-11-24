package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.core.session.ReplacedSession
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.Tokens
import app.tfdn.lirelle.core.session.UserProfile

/** A session as BookOrbit's sign-in gives it: its tokens and the server's id for the session. */
fun bookOrbitSession(serverUrl: String, sessionId: Long, tokens: Tokens, user: UserProfile) =
    Session(serverUrl, Credential.SessionTokens(sessionId, tokens), user)

/** The tokens of a BookOrbit session; fails for any other credential. */
val Session.tokens: Tokens get() = (credential as Credential.SessionTokens).tokens

/** [this] BookOrbit session with another session id or other tokens. */
fun Session.copyCredential(
    sessionId: Long = (credential as Credential.SessionTokens).sessionId,
    tokens: Tokens = this.tokens,
): Session = copy(credential = Credential.SessionTokens(sessionId, tokens))

/** Signs [session] in as a BookOrbit one. */
suspend fun SessionStore.save(session: Session): ReplacedSession? = save(session, ServerKind.BookOrbit)
