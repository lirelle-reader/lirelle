package app.tfdn.lirelle.core.session

/** The kind of server a profile talks to, stored by its name: a kind added later needs no migration. */
enum class ServerKind { BookOrbit, Komga, Kavita, Audiobookshelf }

/**
 * One server and one account on this device.
 *
 * @property id local, random, never reused and never shown: the name of the profile's folders, which therefore
 *   says nothing of the server or the account.
 * @property signInNeeded the server no longer takes its session: the profile is kept with everything it holds
 *   on the device, listed and opened like any other, and waits for its account to sign in again.
 * @property dataFormat the format of what its folders hold ([CurrentDataFormat] for a profile made by this
 *   version). One of an older format has its folders emptied when it is next opened, before anything of it is
 *   built; until then it keeps the format it was stored with, whatever else of it changes.
 */
data class Profile(
    val id: String,
    val kind: ServerKind,
    val session: Session,
    val signInNeeded: Boolean = false,
    val dataFormat: Int = CurrentDataFormat,
) {
    init {
        // Stored, the credential is read as the kind says: another sort would never be read again.
        require(session.credential.format == kind.credentialFormat) { "A $kind profile holds no other credential" }
    }
}
