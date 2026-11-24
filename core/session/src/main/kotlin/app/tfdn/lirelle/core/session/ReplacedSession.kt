package app.tfdn.lirelle.core.session

/**
 * The session of an account that a sign-in to the same account took the place of on this device: the credential
 * its profile held until then. Signing in opens a new session on the server and leaves the old one valid there
 * until it expires; this credential is the only way left to end it. Given only to whoever signed in
 * ([SessionStore.save]), and kept nowhere.
 *
 * @property refused the server had already refused it: the profile waited for a sign-in.
 */
data class ReplacedSession(val credential: Credential, val refused: Boolean)
