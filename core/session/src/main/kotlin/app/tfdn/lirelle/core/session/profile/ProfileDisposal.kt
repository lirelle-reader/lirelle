package app.tfdn.lirelle.core.session.profile

import app.tfdn.lirelle.core.session.ProfileStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a closer, and then what a profile was still doing, is waited for before the closing goes on
 * without it. Long enough for a last write or for a player to stop; short enough that somebody who signs out
 * and in again does not look at a sign-in that seems stuck.
 */
internal const val CloseWaitMillis = 5_000L

/**
 * Throws a closed profile's objects away, and deletes what a removed profile kept on the device.
 *
 * Nothing here may hold for ever the one that follows the store, which calls it: while it waits, nobody can
 * sign in. So what it waits for is waited for a limited time, and what may be long — deleting folders of
 * downloads — is handed over.
 */
internal class ProfileDisposal(
    private val store: ProfileStore,
    private val roots: ProfileRoots,
    private val avatars: ProfileAvatars,
    private val making: GraphMaking,
) {

    /**
     * Runs the closers of [graph], ends what it was doing, then, when the profile was removed, has its
     * folders deleted in [background]. In that order, so that nothing writes into a folder being deleted —
     * save what did not end in time, which is left behind with a scope that is cancelled.
     *
     * The deletion is not waited for: the next profile may open meanwhile. Ids are never used twice, so its
     * folders are never the ones being deleted.
     *
     * @return whether the profile was removed, and its folders handed over to be deleted: whoever looks after
     *   the profiles removed while they slept must not have them deleted a second time.
     */
    suspend fun dispose(graph: ProfileGraph, background: CoroutineScope): Boolean {
        making.lifeOf(graph.component).closers().forEach { run(it, background) }
        val doing = graph.scope.coroutineContext.job
        doing.cancel()
        if (withTimeoutOrNull(CloseWaitMillis) { doing.join() } == null) {
            making.warn("What a profile was doing did not end in time: closing goes on without it", null)
        }
        // Only a profile that was removed loses its folders: one that was merely closed is opened again.
        val removed = store.profile(graph.profileId) == null
        if (removed) background.launch(making.io) { discard(graph.profileId) }
        return removed
    }

    /**
     * A closer that fails or does not end keeps neither the others from running nor the folders from going.
     * It runs apart, in [background], so that even one that does not end when told to can be left behind.
     */
    private suspend fun run(closer: ProfileCloser, background: CoroutineScope) {
        val closing = background.async { runCatching { closer.close() } }
        val outcome = withTimeoutOrNull(CloseWaitMillis) { closing.await() }
        if (outcome == null) {
            closing.cancel()
            making.warn("A closer of a profile did not end in time: closing goes on without it", null)
        } else {
            outcome.exceptionOrNull()?.let { making.warn("A closer of a profile failed", it) }
        }
    }

    /**
     * Deletes the folders of the profile [id] and the picture of its account, which the device keeps beside
     * them ([ProfileAvatars]), then drops its record. The folders first: a record dropped
     * before them would leave folders that are nobody's. When something of them could not be deleted the
     * record stays, as being removed: the next start tries again. Likewise when the record itself could not
     * be dropped — the registry's file cannot be written on a device with no room left: this runs where
     * nobody catches a failure, which would end the app, and at every start again. Called where folders are
     * deleted ([GraphMaking.io]).
     */
    suspend fun discard(id: String) {
        avatars.delete(id)
        if (!roots.delete(id)) {
            making.warn("The folders of a profile could not all be deleted: left for the next start", null)
            return
        }
        try {
            store.forget(id)
        } catch (failure: IOException) {
            making.warn("The record of a removed profile could not be dropped: left for the next start", failure)
        }
    }
}
