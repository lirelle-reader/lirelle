package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.engine.CachedFiles
import app.tfdn.lirelle.core.downloads.engine.ClosingDeletion
import app.tfdn.lirelle.core.downloads.engine.Disposal
import app.tfdn.lirelle.core.downloads.engine.DownloadQueue
import app.tfdn.lirelle.core.downloads.engine.DownloadRemover
import app.tfdn.lirelle.core.downloads.engine.DownloadRunner
import app.tfdn.lirelle.core.downloads.engine.Downloader
import app.tfdn.lirelle.core.downloads.engine.FlaggedBooks
import app.tfdn.lirelle.core.downloads.engine.FormatPlanner
import app.tfdn.lirelle.core.downloads.engine.FormatRun
import app.tfdn.lirelle.core.downloads.engine.FormatSources
import app.tfdn.lirelle.core.downloads.engine.QueueInputs
import app.tfdn.lirelle.core.downloads.engine.RecordStates
import app.tfdn.lirelle.core.downloads.engine.StartCheck
import app.tfdn.lirelle.core.downloads.job.DownloadJobScheduler
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.downloads.update.UpdateCancel
import app.tfdn.lirelle.core.downloads.update.UpdateRun
import app.tfdn.lirelle.core.downloads.update.UpdateSwap
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.library.offline.BookHolds
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The queue of one profile, what runs it and what deletes from it: one of each for as long as it is open. */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsQueueModule {

    /**
     * The permission is the profile's own account's, followed as the server changes it. The network is the
     * profile's: the queue waits, as without one, while the session waits for a sign-in.
     */
    @Provides
    @ProfileScoped
    fun queueInputs(network: ProfileNetwork, settings: DownloadSettingsStore, session: ProfileSession): QueueInputs =
        QueueInputs(
            network = network.state,
            allowMetered = settings.settings.map { it.allowMetered },
            canDownload = session.session.map { it.user.canDownload }.distinctUntilChanged(),
        )

    @Provides
    @ProfileScoped
    fun disposal(registry: DownloadRegistry, files: DownloadFiles, kept: KeptBookData): Disposal =
        Disposal(registry, files, kept, Dispatchers.IO)

    @Provides
    @ProfileScoped
    fun downloadQueue(
        registry: DownloadRegistry,
        disposal: Disposal,
        inputs: QueueInputs,
        swap: UpdateSwap,
    ): DownloadQueue = DownloadQueue(registry, disposal, UpdateCancel(registry, swap), inputs.canDownload, Instant::now)

    /**
     * A deletion waits for its undo window whatever screen asked for it, in a scope nothing can fail. Not
     * beyond the profile: one that is removed loses its folders whole.
     */
    @Provides
    @ProfileScoped
    fun downloadRemover(
        registry: DownloadRegistry,
        disposal: Disposal,
        @ProfileLife life: CoroutineScope,
    ): DownloadRemover = DownloadRemover(
        registry = registry,
        disposal = disposal,
        scope = CoroutineScope(
            life.coroutineContext + SupervisorJob(life.coroutineContext[Job]) + Dispatchers.Default +
                CoroutineExceptionHandler { _, _ -> },
        ),
    )

    @Provides
    @ProfileScoped
    fun flaggedBooks(registry: DownloadRegistry, disposal: Disposal, remover: DownloadRemover): FlaggedBooks =
        FlaggedBooks(registry, disposal, remover::unlessHeld)

    /** What the end panels of the reader and of the player delete a book with. */
    @Provides
    @ProfileScoped
    fun closingDeletions(registry: DownloadRegistry, flagged: FlaggedBooks, queue: DownloadQueue): ClosingDeletions =
        ClosingDeletion(registry, flagged) { queue.cancel(it) }

    @Provides
    @ProfileScoped
    fun formatRun(
        registry: DownloadRegistry,
        files: DownloadFiles,
        planner: FormatPlanner,
        downloader: Downloader,
        caches: Set<@JvmSuppressWildcards CachedFiles>,
    ): FormatRun = FormatRun(
        states = RecordStates(registry, Instant::now),
        files = files,
        sources = FormatSources(planner, downloader, caches),
        io = Dispatchers.IO,
    )

    @Provides
    @ProfileScoped
    fun downloadRunner(
        registry: DownloadRegistry,
        files: DownloadFiles,
        inputs: QueueInputs,
        formats: FormatRun,
        parts: QueueParts,
    ): DownloadRunner = DownloadRunner(
        registry = registry,
        inputs = inputs,
        formats = formats,
        updates = parts.updates,
        queue = parts.queue,
        start = StartCheck(registry, files, parts.remover, parts.flagged, parts.swap, Dispatchers.IO),
        disposal = parts.disposal,
    ).also { runner -> parts.remover.haltWith { block -> runner.halted(block) } }

    /**
     * The profile closes: the system's job is taken away, running or not, so that none runs for a profile that
     * is not open; the next one to open asks for its own. Nothing is deleted here: a profile that is removed
     * loses its folders, and the downloads with them.
     */
    @Provides
    @IntoSet
    fun cancelJobAtClose(scheduler: DownloadJobScheduler): ProfileCloser = ProfileCloser { scheduler.cancel() }
}

/** What the runner shares with the queue and the removals, and what it runs the updates with. */
internal class QueueParts @Inject constructor(
    val queue: DownloadQueue,
    val remover: DownloadRemover,
    val disposal: Disposal,
    val updates: UpdateRun,
    val swap: UpdateSwap,
    val flagged: FlaggedBooks,
)

@Module
@InstallIn(ProfileComponent::class)
internal abstract class DownloadsBindings {

    @Binds
    abstract fun downloads(queue: DownloadQueue): Downloads

    @Binds
    abstract fun removals(remover: DownloadRemover): DownloadRemovals

    @Binds
    abstract fun holds(remover: DownloadRemover): BookHolds

    /** The caches a download may copy a file from: the reader's, the player's. */
    @Multibinds
    abstract fun caches(): Set<CachedFiles>
}
