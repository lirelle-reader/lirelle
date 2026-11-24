package app.tfdn.lirelle.feature.reader.di

import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.feature.player.controller.PlayerController
import app.tfdn.lirelle.feature.player.narration.narrationActions
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import app.tfdn.lirelle.feature.reader.DownloadPermission
import app.tfdn.lirelle.feature.reader.ReaderLeaveScope
import app.tfdn.lirelle.feature.reader.narration.ControllerNarrationPlayer
import app.tfdn.lirelle.feature.reader.narration.ReaderNarrationPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job

/** What the reader takes from the open profile: who may download, its player, and where its last writes run. */
@Module
@InstallIn(ProfileComponent::class)
internal object ReaderProfileModule {

    /** The permission of the profile's account, read again each time it is asked. */
    @Provides
    fun downloadPermission(session: ProfileSession): DownloadPermission =
        DownloadPermission { session.session.value.user.canDownload }

    /** The narration of a read-along book is the profile's one player's. */
    @Provides
    fun narrationPlayer(controller: PlayerController, cursor: NarrationCursor): ReaderNarrationPlayer =
        ControllerNarrationPlayer(controller, cursor, PlaybackServiceRunning.running, controller.narrationActions())

    /**
     * On the main thread, where the position and the session clock are used; nothing can fail it. It outlives
     * the reader that leaves, not the profile: the closing waits for what it runs ([waitForLastWrites]).
     */
    @Provides
    @ProfileScoped
    @ReaderLeaveScope
    fun leaveScope(@ProfileLife life: CoroutineScope): CoroutineScope = CoroutineScope(
        life.coroutineContext + SupervisorJob(life.coroutineContext[Job]) + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, _ -> },
    )

    /**
     * The last writes of a reader just left end before the profile's stores do: those end with the profile's
     * scope, and a write still on its way when it is cancelled is lost — a switch asked right after leaving
     * would drop the place the reader was left at. Waited for as any closer is, a few seconds at most.
     */
    @Provides
    @IntoSet
    fun waitForLastWrites(@ReaderLeaveScope leaving: CoroutineScope): ProfileCloser =
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

/** The reader's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ReaderProfileEntry {
    fun downloadPermission(): DownloadPermission

    fun readerNarrationPlayer(): ReaderNarrationPlayer

    @ReaderLeaveScope
    fun readerLeaveScope(): CoroutineScope
}

/**
 * Gives the reader's view models the objects of the open profile. Unscoped, and in no component that outlives
 * a profile: each view model gets those of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ReaderBridges {

    @Provides
    fun downloadPermission(current: CurrentProfile): DownloadPermission =
        current.entry<ReaderProfileEntry>().downloadPermission()

    @Provides
    fun narrationPlayer(current: CurrentProfile): ReaderNarrationPlayer =
        current.entry<ReaderProfileEntry>().readerNarrationPlayer()

    @Provides
    @ReaderLeaveScope
    fun leaveScope(current: CurrentProfile): CoroutineScope = current.entry<ReaderProfileEntry>().readerLeaveScope()
}
