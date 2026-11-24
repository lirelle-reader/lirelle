package app.tfdn.lirelle

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import app.tfdn.lirelle.core.network.NetworkDiagnostics
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.session.profile.DeviceSignals
import app.tfdn.lirelle.core.session.profile.ProfileGraphs
import app.tfdn.lirelle.profile.ProfileMigration
import app.tfdn.lirelle.profile.warnOfMigration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Open for the tests of the start alone: one of them is the application of a device that already holds an
 * older installation, which only a subclass can write before [onCreate] runs.
 */
@HiltAndroidApp
open class LirelleApplication : Application() {

    @Inject
    lateinit var migration: ProfileMigration

    @Inject
    lateinit var profileGraphs: ProfileGraphs

    @Inject
    lateinit var appThemeAtStart: AppThemeAtStart

    @Inject
    lateinit var signals: DeviceSignals

    override fun onCreate() {
        super.onCreate()
        // Why a call could not reach the server, in logcat: debug builds only.
        if (BuildConfig.DEBUG) NetworkDiagnostics.log = NetworkLog.Logcat
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Before anything else, and not left for later: the current profile's objects exist when this returns,
        // for whatever the system starts next, a service in a fresh process as well as the first screen. What
        // runs for a profile without a screen starts with them, there, not here.
        openProfiles(scope)
        // A restored or freshly installed app agrees with its store.
        appThemeAtStart.start(scope)
        watchNetwork()
        watchFront()
    }

    /**
     * Migrates, then opens the profiles. Apart from [onCreate] for the tests, which run it a second time as
     * the next start of a device whose first one could not finish.
     */
    internal fun openProfiles(scope: CoroutineScope) {
        val open = migrated() && done("The profiles could not be opened") { openGraphs(scope) }
        if (!open) profileGraphs.halt()
    }

    /** Apart, and open, for the test of a start whose opening fails: nothing else can make it fail on demand. */
    protected open fun openGraphs(scope: CoroutineScope) = profileGraphs.open(scope)

    /**
     * An installation from before the profiles becomes its first profile, here and now: before the profiles
     * are opened, and before any store of the application opens a file the migration moves or splits.
     *
     * @return false when it could not end. The account's data is then half in its profile's folders, or not
     *   there yet: the profiles are not opened at this start, which says that the update could not finish
     *   and shows nothing else, neither the account nor the sign-in screen beside it. The next start tries
     *   again.
     */
    private fun migrated(): Boolean = done("The migration to profiles did not end") {
        runBlocking { migration.run() }
    }

    /**
     * Runs a [step] of the start that nothing may throw out of: the migration, then the opening of the
     * profiles, which reads the registry and builds the current profile's objects.
     *
     * Whatever stops it is caught, not only what the files refuse: the keystore failing while the tokens are
     * encrypted again, a permission, an object of the profile that cannot be built, a state nobody foresaw.
     * Thrown from here it would end the process at every start, with no way out for the user but to erase the
     * application's data.
     *
     * @param failed what the log is told then, with the kind of the failure and never its message.
     * @return false when the step failed: this start shows that the update could not finish.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun done(failed: String, step: () -> Unit): Boolean = try {
        step()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        warnOfMigration(failed, failure)
        false
    }

    /**
     * The one activity coming to the screen and leaving it, told to whoever is open: the account, and the
     * books kept on the device, may have changed on the server meanwhile.
     */
    private fun watchFront() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = signals.onShown()
                override fun onActivityStopped(activity: Activity) = signals.onHidden()
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** For as long as the process lives: never unregistered. */
    private fun watchNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = signals.onNetworkAvailable()
            },
        )
    }
}
