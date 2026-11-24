package app.tfdn.lirelle.core.session

import kotlinx.coroutines.flow.StateFlow

/** The profiles of this device, and which one the app shows. Device data. */
interface ProfileStore : ProfileRecords {
    /**
     * The current profile; null while nobody is signed in, and also when no ready profile could be read at
     * this start — the keystore not answering — although the device has some. Meaningful once [load] has
     * returned.
     */
    val current: StateFlow<Profile?>

    /** The ready profiles, in order of use, the current one first. Meaningful once [load] has returned. */
    val profiles: StateFlow<List<Profile>>

    /**
     * Reads the stored profiles. Called once, at start, before anything reads [current]. Every other call
     * here, and every change to the session, suspends until this has returned: whoever calls one of them
     * where nothing calls [load] waits for ever.
     */
    suspend fun load()

    /** The profile [id], current or not; null when it is gone or being removed. */
    suspend fun profile(id: String): Profile?

    /**
     * Writes [credential] to the profile [id], whatever the current one, in the place of [replacing], the one
     * it was renewed from. No-op when it is gone or being removed, and when it no longer holds [replacing]'s
     * sign-in ([Credential.isSameSignIn]): the account signed in again while the renewal was on its way, and an
     * answer about the session of before must not undo that sign-in.
     *
     * @return the profile as this wrote it; null when nothing was written. Whoever keeps that profile's
     *   session learns of its own write from here, without waiting for [current] to tell it.
     */
    suspend fun updateCredential(id: String, credential: Credential, replacing: Credential): Profile?

    /** Likewise for the account. No-op too, and null, when [user] is another account, or nothing changed. */
    suspend fun updateUser(id: String, user: UserProfile): Profile?

    /** Makes the ready profile [id] the current one. No-op when it is already, gone, or not ready. */
    suspend fun switchTo(id: String)

    /**
     * Marks the ready profile [id] as needing sign-in, because the server refused [refused]. No-op, and null,
     * when it is gone or already marked, and when it no longer holds [refused]'s sign-in: the refusal is about
     * a session the account has replaced by signing in again, and says nothing of the new one.
     */
    suspend fun needSignIn(id: String, refused: Credential): Profile?

    /**
     * Marks the profile [id], which sleeps, as being removed: it is never opened again, and its folders are
     * still on the device until [ProfileRecords.forget]. Nobody is moved: the current profile stays.
     *
     * Only while it sleeps: no-op when [id] is the current profile by the time the change is applied, and
     * when it is gone or already being removed. Whoever removes a profile from a list looked at who was
     * current when the user answered; a switch that lands after that look would otherwise have the current
     * profile removed as one that sleeps — its server not told, and nothing said of what it had not sent yet.
     * The current one is removed by signing it out ([SessionStore.clear]), which is the opposite condition.
     * There is no removal that does not say which of the two it is.
     *
     * @return whether this call removed the profile.
     */
    suspend fun removeSleeping(id: String): Boolean
}

/**
 * What the registry lists beside the profiles that may be opened, for whoever looks after their folders: which
 * ones are to be deleted, which ones are not whole yet, and which ones are somebody's at all.
 */
interface ProfileRecords {
    /** The ids of the profiles being removed, whose folders may still exist. */
    suspend fun beingRemoved(): List<String>

    /**
     * The ids of the profiles the migration is still moving the old files into: listed, so that their folders
     * are theirs, and never opened. While there is one, the device has an account whose data is half here:
     * nothing may be shown of it, and nobody may sign in beside it.
     */
    suspend fun beingMoved(): List<String>

    /** Drops the record of a profile being removed, once its folders are gone. */
    suspend fun forget(id: String)

    /**
     * Every id the registry still lists, being removed or not: a folder of any other id is nobody's.
     *
     * Null when the registry could not be read at this start: what it lists is then not known, which is not
     * the same as listing nothing, and no folder may be deleted for being absent from it. It stays null until
     * the next start, whatever is saved meanwhile.
     *
     * Whoever sweeps the folders that are nobody's lists the folders first and asks for the ids after: a
     * profile saved in between then has its id here and its folder not yet among those seen. The other way
     * round, its folder would be seen without its id, and deleted.
     */
    suspend fun listedIds(): Set<String>?
}
