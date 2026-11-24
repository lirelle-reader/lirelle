package app.tfdn.lirelle.di

import app.tfdn.lirelle.AccountRefresher
import app.tfdn.lirelle.AvatarFetcher
import app.tfdn.lirelle.KeptBooksChecker
import app.tfdn.lirelle.OutboxKicker
import app.tfdn.lirelle.core.downloads.job.BackgroundDownloads
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.DeviceSignals
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileStarter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * What runs without a screen for the profile that is open, started with its objects and stopped with them.
 * The application tells the device's signals to nobody in particular ([DeviceSignals]); each starter follows
 * them in the profile's scope, so a profile that has closed hears nothing more.
 *
 * The network coming back is the profile's own news ([ProfileNetwork.returns]), not the device's: a profile
 * whose session waits for a sign-in hears nothing of the device's network, and hears of the sign-in as it
 * would of a network that came back.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ProfileStartersModule {

    /** What was read without a network is sent even if the reader is not opened again. */
    @Provides
    @IntoSet
    fun outbox(kicker: OutboxKicker, network: ProfileNetwork): ProfileStarter = ProfileStarter { scope ->
        kicker.start()
        scope.launch { network.returns.collect { kicker.onNetworkAvailable() } }
    }

    /** What the account may do can change on the server while the app is closed, or behind another. */
    @Provides
    @IntoSet
    fun account(
        refresher: AccountRefresher,
        signals: DeviceSignals,
        network: ProfileNetwork,
    ): ProfileStarter = ProfileStarter { scope ->
        refresher.start(scope)
        scope.launch { network.returns.collect { refresher.onNetworkAvailable() } }
        // Not where the app stands as the profile opens: starting has just refreshed.
        scope.launch { signals.inFront.drop(1).filter { it }.collect { refresher.onShown() } }
    }

    /**
     * What is kept may have changed or vanished on the server: looked at whenever the app is in front, and
     * at once for a profile that opens while it is there.
     */
    @Provides
    @IntoSet
    fun keptBooks(
        checker: KeptBooksChecker,
        signals: DeviceSignals,
        network: ProfileNetwork,
    ): ProfileStarter = ProfileStarter { scope ->
        checker.start(scope)
        scope.launch { signals.inFront.collect { if (it) checker.onShown() else checker.onHidden() } }
        scope.launch { network.returns.collect { checker.onNetworkAvailable() } }
    }

    /**
     * The picture of the account is kept on the device, for its avatar; it follows the session by itself,
     * and the profile's network, so it is told of nothing here.
     */
    @Provides
    @IntoSet
    fun avatar(fetcher: AvatarFetcher): ProfileStarter = ProfileStarter { fetcher.start(it) }

    /** A download asked starts at once, here; the system's job goes on with it once the app is left or killed. */
    @Provides
    @IntoSet
    fun downloads(background: BackgroundDownloads): ProfileStarter = ProfileStarter { background.start(it) }
}
