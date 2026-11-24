package app.tfdn.lirelle.feature.comics.di

import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.feature.comics.ComicNetwork
import app.tfdn.lirelle.feature.comics.ComicTimings
import app.tfdn.lirelle.feature.comics.ComicsLeaveScope
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job

/** What the comics reader takes from the device, whoever is signed in. */
@Module
@InstallIn(SingletonComponent::class)
internal object ComicsFeatureModule {

    @Provides
    fun timings(): ComicTimings = ComicTimings()
}

/** What the comics reader takes from the open profile. */
@Module
@InstallIn(ProfileComponent::class)
internal object ComicsProfileModule {

    /**
     * On the main thread, where the position and the session clock are used; nothing can fail it. It outlives
     * the reader that leaves, not the profile: the closing waits for what it runs ([waitForLastWrites]).
     */
    @Provides
    @ProfileScoped
    @ComicsLeaveScope
    fun leaveScope(@ProfileLife life: CoroutineScope): CoroutineScope = CoroutineScope(
        life.coroutineContext + SupervisorJob(life.coroutineContext[Job]) + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, _ -> },
    )

    /**
     * The last writes of a comics reader just left end before the profile's stores do: those end with the
     * profile's scope, and a write still on its way when it is cancelled is lost. Waited for as any closer is,
     * a few seconds at most.
     */
    @Provides
    @IntoSet
    fun waitForLastWrites(@ComicsLeaveScope leaving: CoroutineScope): ProfileCloser =
        ProfileCloser {
            // Until none is left: a write started while others are waited for is waited for too.
            val job = leaving.coroutineContext.job
            while (true) {
                val running = job.children.toList()
                if (running.isEmpty()) break
                running.joinAll()
            }
        }
}

/** The comics reader's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ComicsProfileEntry {
    @ComicsLeaveScope
    fun comicsLeaveScope(): CoroutineScope
}

/**
 * Gives the comics reader's view models the objects of the open profile. Unscoped, and in no component that
 * outlives a profile: each view model gets those of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ComicsBridges {

    @Provides
    @ComicsLeaveScope
    fun leaveScope(current: CurrentProfile): CoroutineScope = current.entry<ComicsProfileEntry>().comicsLeaveScope()

    /** The reader hears of the network from its profile, which has none while its session waits for a sign-in. */
    @Provides
    fun network(profile: ProfileNetwork): ComicNetwork = ComicNetwork(profile.online)
}
