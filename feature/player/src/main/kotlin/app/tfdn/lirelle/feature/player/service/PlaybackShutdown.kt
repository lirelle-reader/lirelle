package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stops the playback service when its profile closes, before the profile's objects are thrown away and what
 * it kept is deleted: nothing goes on playing, or reading the cache, for an account that left.
 *
 * The profile closes on another thread than the one the service is made on, and may do so while the service
 * is being made: once [stopPlayback] has run, a service that comes to [register] is refused, and ends by
 * itself. Without that it would be told nothing, and play on with the objects of a profile that closed.
 *
 * It also knows whether a service of its profile still has a player ([reading]): its own profile's, not
 * "whether the service runs" for the whole process, which the next profile's service would answer too.
 */
@ProfileScoped
class PlaybackShutdown @Inject constructor() {
    private val lock = Any()
    private var stop: (suspend () -> Unit)? = null
    private var closed = false

    private val mutableReading = MutableStateFlow(false)

    /**
     * Whether a service made for this profile has a player, which may read the profile's files. It outlasts
     * [PlaybackServiceRunning.running] by the last steps of the service's end: that one must be false before
     * the session is let go, so that no screen connects again, and the player is released after that. What
     * the player reads through — the audio cache, which locks its folder — is let go on this one, whatever
     * service the next profile has started meanwhile.
     */
    val reading: StateFlow<Boolean> = mutableReading.asStateFlow()

    /** Called by the service made for this profile, before it makes its player. */
    fun playerMade() {
        mutableReading.value = true
    }

    /** Called by that service, ending, once its player is released. */
    fun playerReleased() {
        mutableReading.value = false
    }

    /**
     * The running service: [stop] closes its book and ends it, and returns once its last writes are made.
     *
     * @return false when the profile has closed already: nothing is kept, and the caller ends at once.
     */
    fun register(stop: suspend () -> Unit): Boolean = synchronized(lock) {
        if (!closed) this.stop = stop
        !closed
    }

    /** The service ended by itself: another may run for the same profile later. */
    fun unregister() = synchronized(lock) { stop = null }

    /** The profile closes. Does nothing more when no service runs. */
    suspend fun stopPlayback() {
        val running = synchronized(lock) {
            closed = true
            stop.also { stop = null }
        }
        running?.invoke()
    }
}
