package app.tfdn.lirelle.core.downloads.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.engine.DownloadRemover
import app.tfdn.lirelle.core.downloads.engine.Downloader
import app.tfdn.lirelle.core.downloads.engine.FileRun
import app.tfdn.lirelle.core.downloads.engine.FlaggedBooks
import app.tfdn.lirelle.core.downloads.engine.FormatPlanner
import app.tfdn.lirelle.core.downloads.engine.FormatSources
import app.tfdn.lirelle.core.downloads.engine.QueueInputs
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.update.CheckClock
import app.tfdn.lirelle.core.downloads.update.DataStoreCheckClock
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.downloads.update.RegistryDownloadUpdates
import app.tfdn.lirelle.core.downloads.update.UpdateCheck
import app.tfdn.lirelle.core.downloads.update.UpdateInputs
import app.tfdn.lirelle.core.downloads.update.UpdateRun
import app.tfdn.lirelle.core.downloads.update.UpdateStates
import app.tfdn.lirelle.core.downloads.update.UpdateSwap
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Instant
import kotlinx.coroutines.Dispatchers

/** How a profile's kept books follow the server: the check, its clock, and the update fetched beside the copy. */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsUpdateModule {

    /**
     * Beside the registry, in the profile's file, under a key of its own: a profile new to the device has no
     * hour, and its first pass is its own.
     */
    @Provides
    @ProfileScoped
    fun checkClock(@DownloadsDataStore dataStore: DataStore<Preferences>): CheckClock =
        DataStoreCheckClock(dataStore)

    @Provides
    @ProfileScoped
    fun downloadUpdates(
        registry: DownloadRegistry,
        planner: FormatPlanner,
        clock: CheckClock,
        network: ProfileNetwork,
        inputs: QueueInputs,
    ): DownloadUpdates = RegistryDownloadUpdates(
        registry = registry,
        check = UpdateCheck(registry, planner),
        clock = clock,
        inputs = UpdateInputs({ network.isOnline }, inputs.canDownload),
        now = Instant::now,
    )

    /**
     * The swap knows the holds, and a book let go of has its whole updates swapped: the remover tells it. A
     * book to delete when it closes is told first, and goes: nothing is swapped into it.
     */
    @Provides
    @ProfileScoped
    fun updateSwap(
        registry: DownloadRegistry,
        files: DownloadFiles,
        remover: DownloadRemover,
        flagged: FlaggedBooks,
    ): UpdateSwap {
        remover.whenReleased(flagged::delete)
        return UpdateSwap(registry, files, remover::unlessHeld, Dispatchers.IO)
            .also { remover.whenReleased(it::settle) }
    }

    /** The pieces of a download, pointed at the places beside the kept files. */
    @Provides
    @ProfileScoped
    @BesideKept
    fun updateFileRun(
        files: DownloadFiles,
        planner: FormatPlanner,
        @BesideKept downloader: Downloader,
        caches: Set<@JvmSuppressWildcards CachedFiles>,
    ): FileRun = FileRun(files.updates, FormatSources(planner, downloader, caches), Dispatchers.IO)

    @Provides
    @ProfileScoped
    fun updateRun(
        registry: DownloadRegistry,
        files: DownloadFiles,
        @BesideKept fileRun: FileRun,
        inputs: QueueInputs,
        swap: UpdateSwap,
    ): UpdateRun = UpdateRun(UpdateStates(registry), files, fileRun, inputs, swap)
}
