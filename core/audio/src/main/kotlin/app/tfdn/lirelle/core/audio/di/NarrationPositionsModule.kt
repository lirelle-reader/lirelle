package app.tfdn.lirelle.core.audio.di

import app.tfdn.lirelle.core.audio.narration.DefaultNarrationCursor
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn

@Module
@InstallIn(ProfileComponent::class)
internal object NarrationPositionsModule {

    /** One for the profile: the service tells it, the reader follows it, and both are that profile's. */
    @Provides
    @ProfileScoped
    fun narrationCursor(): NarrationCursor = DefaultNarrationCursor()

    /** One per player: it remembers when it last sent. It writes where the reading of the same book is kept. */
    @Provides
    fun narrationPositionWriter(
        store: NarrationStore,
        outbox: Outbox,
        flusher: OutboxFlusher,
        clock: MonotonicClock,
    ): NarrationPositionWriter = NarrationPositionWriter(store, outbox, flusher, clock)
}
