package app.tfdn.lirelle.core.session

/**
 * The registry with the profile [id] as the one used last: written as current, and first of those listed.
 *
 * The profile left comes right behind it, then the one written as current when that is another — one that
 * could not be read at this start, which was used before the one shown in its place — and the others keep
 * their order. The list is put in that order here, whatever it was: a file from before the order was kept
 * lists its current profile anywhere.
 */
internal fun RegistrySnapshot.fronted(id: String): RegistrySnapshot {
    val front = listOfNotNull(id, current?.id, stored.current).distinct()
    val (used, others) = stored.profiles.partition { it.id in front }
    return copy(stored = stored.copy(profiles = used.sortedBy { front.indexOf(it.id) } + others, current = id))
}

/**
 * The ready profile [id] becomes the current one. Null when it is already, or cannot be opened: gone, being
 * removed, being moved in, or not read at this start.
 */
internal fun RegistrySnapshot.switching(id: String): RegistrySnapshot? =
    if (id !in ready || current?.id == id) null else fronted(id)

/**
 * The server no longer takes the session of the profile [id]: it is marked, and nothing else of it changes. It
 * stays where it is in the order of use, and current if it was. Null when it cannot be opened, or is marked
 * already, or no longer holds [refused], the credential the server would not take: its account signed in again
 * while that answer was on its way, and the refusal is about the session of before.
 */
internal fun RegistrySnapshot.needingSignIn(id: String, refused: Credential, codec: ProfileCodec): RegistrySnapshot? =
    ready[id]?.takeIf { !it.signInNeeded && it.holds(refused) }?.let { with(it.copy(signInNeeded = true), codec) }

/**
 * Whether [credential] is still this profile's sign-in: an answer of the server about it — renewed, refused — is
 * then about the session the profile has now ([Credential.isSameSignIn]).
 */
internal fun Profile.holds(credential: Credential): Boolean = session.credential.isSameSignIn(credential)
