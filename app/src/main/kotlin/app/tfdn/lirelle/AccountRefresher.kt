package app.tfdn.lirelle

import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Has the account re-read whenever the server may answer: when its profile opens, when the application
 * comes to the front and when the network comes back. What the account may do (downloading, for one) can change on the
 * server at any time; a refresh that fails leaves what is known as it is.
 */
@ProfileScoped
class AccountRefresher @Inject constructor(private val account: AccountRepository) {

    @Volatile
    private var scope: CoroutineScope? = null

    /** Refreshes now, then on each [onNetworkAvailable], for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        refresh()
    }

    /**
     * The profile may reach its server again: the device has a network again, or somebody signed in again to
     * a session that had expired. Does nothing before [start].
     */
    fun onNetworkAvailable() = refresh()

    /** The application comes to the front: what changed on the server meanwhile is seen without a restart. */
    fun onShown() = refresh()

    private fun refresh() {
        scope?.launch { account.refreshAccount() }
    }
}
