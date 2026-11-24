package app.tfdn.lirelle.feature.book.di

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptPositions
import app.tfdn.lirelle.core.downloads.offline.LocalProgress
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.feature.book.download.DownloadedPages
import app.tfdn.lirelle.feature.book.download.KeptPages
import app.tfdn.lirelle.feature.book.download.KeptPlaces
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** The book pages the device keeps are the open profile's downloads. */
@Module
@InstallIn(ProfileComponent::class)
internal object BookDownloadsModule {

    @Provides
    fun keptPages(
        downloads: Downloads,
        kept: KeptBookData,
        progress: LocalProgress,
        positions: KeptPositions,
    ): KeptPages = DownloadedPages(
        downloads,
        kept,
        KeptPlaces(progress::percentOf, progress::listeningOf, positions::refresh, progress::ownOf),
    )
}

/** The book page's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface BookProfileEntry {
    fun bookKeptPages(): KeptPages
}

/**
 * Gives the book page's view models the objects of the open profile. Unscoped, and in no component that
 * outlives a profile: each view model gets those of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object BookBridges {

    @Provides
    fun keptPages(current: CurrentProfile): KeptPages = current.entry<BookProfileEntry>().bookKeptPages()
}
