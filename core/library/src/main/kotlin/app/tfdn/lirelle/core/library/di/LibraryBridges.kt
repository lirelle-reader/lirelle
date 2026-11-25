package app.tfdn.lirelle.core.library.di

import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.RecentSearches
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** The library's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface LibraryProfileEntry {
    fun recentSearches(): RecentSearches

    fun libraryChoice(): LibraryChoice

    fun libraryRepository(): LibraryRepository

    fun libraryAudioRepository(): AudioRepository

    fun libraryReaderRepository(): ReaderRepository

    fun libraryReadingChanges(): ReadingChanges

    fun librarySourceCapabilities(): SourceCapabilities
}

/**
 * Gives view models the library's objects of the open profile. Unscoped, and in no component that outlives
 * a profile: each view model gets those of the profile open when it is made. View models only: a bridge may
 * answer with the profile closed last, which a service must never act on.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object LibraryBridges {

    @Provides
    fun recentSearches(current: CurrentProfile): RecentSearches = current.entry<LibraryProfileEntry>().recentSearches()

    @Provides
    fun libraryChoice(current: CurrentProfile): LibraryChoice = current.entry<LibraryProfileEntry>().libraryChoice()

    /** What the source of the profile open when the view model is made can do: a switch makes new view models. */
    @Provides
    fun capabilities(current: CurrentProfile): SourceCapabilities =
        current.entry<LibraryProfileEntry>().librarySourceCapabilities()
}

/**
 * Gives view models what the open profile's server says of its books, and the word that this device changed
 * it. Unscoped, and in no component that outlives a profile: each view model gets those of the profile open
 * when it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object LibraryRepositoryBridges {

    @Provides
    fun library(current: CurrentProfile): LibraryRepository = current.entry<LibraryProfileEntry>().libraryRepository()

    @Provides
    fun audio(current: CurrentProfile): AudioRepository = current.entry<LibraryProfileEntry>().libraryAudioRepository()

    @Provides
    fun reader(current: CurrentProfile): ReaderRepository =
        current.entry<LibraryProfileEntry>().libraryReaderRepository()

    @Provides
    fun readingChanges(current: CurrentProfile): ReadingChanges =
        current.entry<LibraryProfileEntry>().libraryReadingChanges()
}
