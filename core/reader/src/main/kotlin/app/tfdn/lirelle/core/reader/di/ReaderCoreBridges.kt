package app.tfdn.lirelle.core.reader.di

import app.tfdn.lirelle.core.reader.cache.ReaderCache
import app.tfdn.lirelle.core.reader.marks.MarkOutbox
import app.tfdn.lirelle.core.reader.marks.Marks
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** The reading objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ReaderCoreProfileEntry {
    fun readerCache(): ReaderCache

    fun readingPositionStore(): PositionStore

    fun readingOutbox(): Outbox

    fun readingOutboxFlusher(): OutboxFlusher

    fun readingNarrationStore(): NarrationStore

    fun readerMarks(): Marks

    fun readerMarkOutbox(): MarkOutbox
}

/**
 * Gives view models the reading objects of the open profile. Unscoped, and in no component that outlives a
 * profile: each view model gets those of the profile open when it is made. View models only: a bridge may
 * answer with the profile closed last, which a service must never act on.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ReaderCoreBridges {

    @Provides
    fun readerCache(current: CurrentProfile): ReaderCache = current.entry<ReaderCoreProfileEntry>().readerCache()
}

/**
 * Gives view models the reading of the open profile: its positions, its marks and what waits to be sent.
 * Unscoped, and in no component that outlives a profile: each view model gets that of the profile open when
 * it is made.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object ReadingBridges {

    @Provides
    fun positionStore(current: CurrentProfile): PositionStore =
        current.entry<ReaderCoreProfileEntry>().readingPositionStore()

    @Provides
    fun outbox(current: CurrentProfile): Outbox = current.entry<ReaderCoreProfileEntry>().readingOutbox()

    @Provides
    fun outboxFlusher(current: CurrentProfile): OutboxFlusher =
        current.entry<ReaderCoreProfileEntry>().readingOutboxFlusher()

    @Provides
    fun narrationStore(current: CurrentProfile): NarrationStore =
        current.entry<ReaderCoreProfileEntry>().readingNarrationStore()

    @Provides
    fun marks(current: CurrentProfile): Marks = current.entry<ReaderCoreProfileEntry>().readerMarks()

    @Provides
    fun markOutbox(current: CurrentProfile): MarkOutbox = current.entry<ReaderCoreProfileEntry>().readerMarkOutbox()
}
