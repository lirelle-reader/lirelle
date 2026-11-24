package app.tfdn.lirelle.core.session

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The session of the current profile, for what happens without a profile: signing in, signing out, and the
 * server remembered for the next sign-in.
 *
 * Nothing here writes a credential or an account to "whoever is current": a call still in flight when its profile
 * closes would write one account's renewed tokens into another. Those writes name their profile
 * ([ProfileStore.updateCredential], [ProfileStore.updateUser]), and a profile's objects reach them through their
 * own `ProfileSession`.
 */
interface SessionStore {
    val state: StateFlow<SessionState>

    /**
     * The server's address remembered for the next sign-in: the last one typed and checked, or the one last
     * signed in to or out of; null while none was. Device data, which no profile owns: it is read and written
     * whoever is signed in, and [SessionState.SignedOut] carries the same value.
     */
    val serverUrl: StateFlow<String?>

    /**
     * The kind of server at [serverUrl]: the one it was remembered with, or the kind of the profile last signed in
     * to or out of. Null while no address is remembered, or when the kind written is not one this version knows
     * (it stays written until another server is remembered). An address remembered by a version that knew
     * BookOrbit alone, without a kind, is a BookOrbit server's.
     */
    val serverKind: StateFlow<ServerKind?>

    /**
     * Remembers [serverUrl], a server of [kind], for the next sign-in, signed in or not: a profile is added from
     * the sign-in screens shown over the current one, which stays as it is.
     */
    suspend fun setServerUrl(serverUrl: String, kind: ServerKind)

    /**
     * Persists [session], signed in to a server of [kind], then publishes [SessionState.SignedIn]: its account's
     * profile — of that kind, server and account — made when the device has none, becomes the current one
     * beside the others. An OIDC sign-in that was still waiting for its redirect waits no more: whoever signs in
     * ends it.
     *
     * @return the session of the same account whose credential [session] replaced, for its server to be told:
     *   the profile it is saved to held it. Null when that profile is new, or is one whose credential could not
     *   be read at this start.
     */
    suspend fun save(session: Session, kind: ServerKind): ReplacedSession?

    /**
     * The current profile, null when there is none: its session together with the id that names it. Whoever
     * signs out reads both here, in one read, and gives that id to [clear]: the session of [state] alone does
     * not say whose it is.
     */
    val current: StateFlow<Profile?>

    /**
     * Signs out of the profile [profileId], which whoever asks read as the current one: it is removed, the
     * profile used last before it becomes current, and when it was the last, this signs out and keeps the
     * server address.
     *
     * The profile is named because removing one makes another current: a sign-out that waited behind another
     * — a second tap — would otherwise remove the profile that just took its place. No-op when [profileId] is
     * no longer the current profile: the first reason stands, and nobody else is removed. (A session that
     * expires is not such a sign-out: it removes nobody, and marks its profile.)
     *
     * @return whether this call removed the profile: false tells a sign-out that there is nothing left for it
     *   to finish.
     */
    suspend fun clear(profileId: String, reason: SignOutReason): Boolean
}

/** Suspends until the stored session has been read. */
suspend fun SessionStore.awaitLoaded(): SessionState = state.first { it != SessionState.Loading }
