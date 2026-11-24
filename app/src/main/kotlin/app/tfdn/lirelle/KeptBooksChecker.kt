package app.tfdn.lirelle

import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Has the kept books checked against the server while the application is in front: when it comes there, and
 * when the network comes back while it is there (the arrival that found no network checked nothing). It is one
 * profile's and checks in that profile's scope: nothing is checked once the profile has closed. Asking often
 * costs nothing: the check itself does nothing without a network or within the hour of its last full pass, and
 * runs one pass at a time.
 *
 * @param check the check of every kept book, when its hour has passed.
 */
@ProfileScoped
class KeptBooksChecker(private val check: suspend () -> Unit) {

    @Inject
    constructor(updates: DownloadUpdates) : this({ updates.check() })

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var inFront = false

    /** Checks nothing yet: from now on, [onShown] and [onNetworkAvailable] do, for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** The application comes to the front, or is there as the profile opens. Does nothing before [start]. */
    fun onShown() {
        inFront = true
        checkNow()
    }

    /** The application leaves the front: a network that comes back meanwhile checks nothing. */
    fun onHidden() {
        inFront = false
    }

    /**
     * The profile may reach its server again: the device has a network again, or somebody signed in again to
     * a session that had expired.
     */
    fun onNetworkAvailable() {
        if (inFront) checkNow()
    }

    private fun checkNow() {
        scope?.launch { check() }
    }
}
