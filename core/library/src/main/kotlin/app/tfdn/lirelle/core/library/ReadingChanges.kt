package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Says that this device changed what the server holds about the user's reading: a position, a
 * reading session or a rating reached it. The screens that show such things load them again when
 * it does, instead of waiting to be old enough.
 *
 * It says neither which book nor what changed. A [version] rather than an event: a screen that
 * looks late, or after several changes, still sees that the version is no longer the one it knew.
 *
 * One per profile: it speaks of one account's reading, to the screens of that account.
 */
@ProfileScoped
class ReadingChanges @Inject constructor() {
    private val mutableVersion = MutableStateFlow(0L)

    /** Goes up by one at each change, and never down. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    /** The server took something this device sent. Safe from any thread. */
    fun changed() = mutableVersion.update { it + 1 }
}
