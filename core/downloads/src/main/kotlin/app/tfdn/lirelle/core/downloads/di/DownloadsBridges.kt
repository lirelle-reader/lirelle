package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Gives view models the downloads of the open profile. Unscoped, and in no component that outlives a profile:
 * each view model gets those of the profile open when it is made.
 *
 * View models only: a bridge answers a screen that is leaving with the profile closed last. The job service
 * and the receivers act, so they take the open profile or nothing ([DownloadsProfileEntry], asked through
 * `openEntryOrNull`).
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object DownloadsBridges {

    @Provides
    fun downloads(current: CurrentProfile): Downloads = current.entry<DownloadsProfileEntry>().downloadsQueue()

    @Provides
    fun removals(current: CurrentProfile): DownloadRemovals =
        current.entry<DownloadsProfileEntry>().downloadRemovals()

    @Provides
    fun closingDeletions(current: CurrentProfile): ClosingDeletions =
        current.entry<DownloadsProfileEntry>().downloadClosingDeletions()

    @Provides
    fun surroundings(current: CurrentProfile): DownloadSurroundings =
        current.entry<DownloadsProfileEntry>().downloadSurroundings()

    @Provides
    fun updates(current: CurrentProfile): DownloadUpdates = current.entry<DownloadsProfileEntry>().downloadUpdates()

    @Provides
    fun files(current: CurrentProfile): DownloadFiles = current.entry<DownloadsProfileEntry>().downloadFiles()
}

/** Likewise for what the device keeps of the books, which the screens look at before the network. */
@Module
@InstallIn(ViewModelComponent::class)
internal object KeptBridges {

    @Provides
    fun keptBookData(current: CurrentProfile): KeptBookData = current.entry<DownloadsProfileEntry>().keptBookData()

    @Provides
    fun downloadMarks(current: CurrentProfile): DownloadMarks = current.entry<DownloadsProfileEntry>().downloadMarks()
}
