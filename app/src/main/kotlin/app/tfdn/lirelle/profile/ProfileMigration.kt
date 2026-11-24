package app.tfdn.lirelle.profile

import android.content.Context
import app.tfdn.lirelle.core.session.MovingProfile
import app.tfdn.lirelle.core.session.SessionAdoption
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileRoots
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Makes the first profile of an installation from before the profiles, which kept the one account signed in
 * at the root of the application's folders: its session becomes a profile, and everything that is the
 * account's goes into that profile's folders. Once, at the first start of the version that has profiles,
 * before any profile is opened and before any store of the application opens a file.
 *
 * It is made to be interrupted anywhere and run again:
 *
 * 1. the session becomes a profile *being moved in*, which is listed and never opened; with no session, the
 *    account's old files are nobody's — deleted, or set aside for the start's sweep to delete in the
 *    background when they are folders — and the server address is kept;
 * 2. each old place is moved into the profile's folders by a rename — whole at one place or the other; the
 *    audio cache, which cannot follow, is renamed aside for the start's sweep to delete in the background,
 *    and its index is deleted; the last book is written in the profile's file;
 * 3. the three files that mixed the device's settings and the account's are split;
 * 4. what the moved stores hold of another account is removed;
 * 5. the profile becomes ready and current, and the old session file is deleted.
 *
 * The old session file is the proof that something is left to do: with no registry yet, everything; with a
 * profile being moved in, from the second step on, for that profile; otherwise only its own deletion. A step
 * that fails throws: the profile stays being moved in, nothing is opened at this start, and the next tries
 * again.
 *
 * Not every step is worth a start, though. What only tidies up — the old audio cache and its index, what
 * DataStore left beside a file that was moved, the old session file once the profile is ready — is tried,
 * and a failure there is told to the log and passed over ([tidy]): the account's data is whole without it,
 * and the user is not kept out of it for a leftover.
 *
 * @param reached told each time a part is done, with its name. For the tests, which interrupt it there.
 * @param warn told of a tidying that failed. Never given a path, an address, an account or a title.
 */
class ProfileMigration internal constructor(
    private val context: Context,
    private val adoption: SessionAdoption,
    private val roots: ProfileRoots,
    private val reached: (String) -> Unit,
    private val warn: (message: String, failure: Throwable) -> Unit = ::warnOfMigration,
) {
    @Inject
    constructor(@ApplicationContext context: Context, adoption: SessionAdoption, roots: ProfileRoots) :
        this(context, adoption, roots, reached = {})

    /**
     * Does what is left to do, which is nothing for an installation that never was without profiles.
     *
     * @throws IOException when a part could not be done. Its message names files of the application at most:
     *   never the server, the account, a token or a book.
     */
    suspend fun run() {
        if (!adoption.due()) return
        val moving = if (adoption.started()) adoption.moving() else begin()
        if (moving != null) moveIn(moving)
        // Left there, it only makes the next start come here again, and find nothing else to do.
        tidy("finished") { adoption.finish() }
    }

    /**
     * The first step. With nobody signed in, the account's files go before the registry is written: an
     * interruption in between starts here again, and one after finds nothing left to delete.
     */
    private suspend fun begin(): MovingProfile? {
        val old = adoption.read()
        if (old.session == null) discard()
        return adoption.adopt(old).also { reached("adopted") }
    }

    /**
     * Nobody was signed in: what the account that left kept is nobody's. The device's settings stay. Its
     * folders are out of the old places when this returns, and deleted later, by the start's sweep: deleting
     * its downloads here would hold the first screen for as long as it takes.
     */
    private suspend fun discard() {
        MovedPlaces.forEach { place ->
            part("deleted ${place.root} ${place.name}") { discard(place, context, roots) }
            tidyLeftoversOf(place)
        }
        putOldAudioAway()
        part("deleted old last book") { deleteOldLastBook(context) }
        Splits.forEach { split -> part("removed ${split.profile}") { split.run(context, folders = null) } }
    }

    /** The steps two to five, for a profile being moved in: by this start or by one that was interrupted. */
    private suspend fun moveIn(profile: MovingProfile) {
        val folders = roots.of(profile.id)
        val account = StoredOwner(profile.serverUrl, profile.userId)
        moveFiles(folders, account)
        Splits.forEach { split -> part("split ${split.profile}") { split.run(context, folders) } }
        OwnedStores.forEach { (store, dropped) ->
            part("owner of $store") { keepOnlyOf(account, folders.dataStore(store), dropped) }
        }
        part("settled") { adoption.settle(profile.id) }
    }

    /** The second step: the preferences files are moved as files, before anything opens them. */
    private suspend fun moveFiles(folders: ProfileFolders, account: StoredOwner) {
        MovedPlaces.forEach { place ->
            part("moved ${place.root} ${place.name}") { move(place.old(context), place.new(folders)) }
            tidyLeftoversOf(place)
        }
        putOldAudioAway()
        part("moved last book") { moveOldLastBook(context, folders, account) }
        part("deleted old last book") { deleteOldLastBook(context) }
    }

    /** Once a preferences file has left the old place, what DataStore left beside it is nobody's. */
    private suspend fun tidyLeftoversOf(place: MovedPlace) {
        if (place.root != OldRoot.Preferences) return
        tidy("tidied ${place.name}") { deleteLeftoversOf(place.old(context)) }
    }

    /**
     * The old audio cache is nobody's from now on: its folder is set aside for the start's sweep, which
     * deletes it in the background, and its index is deleted. In two parts, so that an interruption between
     * them is one the tests can make.
     */
    private suspend fun putOldAudioAway() {
        tidy("set audio aside") { setOldAudioCacheAside(context, roots) }
        tidy("deleted audio index") { deleteOldAudioIndex(context) }
    }

    /** A part the migration cannot do without: a failure ends this start's attempt. */
    private suspend fun part(name: String, run: suspend () -> Unit) {
        run()
        reached(name)
    }

    /**
     * A part that only tidies up: whatever goes wrong in it is told and passed over, and the migration goes
     * on as if it were done. Its name is all the log is given of it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun tidy(name: String, run: suspend () -> Unit) {
        try {
            run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            warn("A tidying of the migration to profiles failed and is passed over ($name)", failure)
        }
        reached(name)
    }
}
