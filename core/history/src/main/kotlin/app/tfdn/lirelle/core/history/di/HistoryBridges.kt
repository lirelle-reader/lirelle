package app.tfdn.lirelle.core.history.di

import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * The history's objects of the open profile, for what Hilt cannot inject them into. The repository is the profile's
 * source's; the cache of reads is the profile's own.
 */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface HistoryProfileEntry {
    fun historyRepository(): HistoryRepository

    fun historyReadRecordCache(): ReadRecordCache
}

/**
 * Gives view models the history of the open profile. Unscoped, and in no component that outlives a profile:
 * each view model gets that of the profile open when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object HistoryBridges {

    @Provides
    fun repository(current: CurrentProfile): HistoryRepository =
        current.entry<HistoryProfileEntry>().historyRepository()

    @Provides
    fun readRecordCache(current: CurrentProfile): ReadRecordCache =
        current.entry<HistoryProfileEntry>().historyReadRecordCache()
}
