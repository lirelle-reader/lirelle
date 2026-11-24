package app.tfdn.lirelle.core.reader.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.ReadingFiles
import app.tfdn.lirelle.core.reader.sync.DataStoreNarrationStore
import app.tfdn.lirelle.core.reader.sync.DataStoreOutbox
import app.tfdn.lirelle.core.reader.sync.DataStorePositionStore
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.reader.sync.ReadingWrites
import app.tfdn.lirelle.core.reader.sync.forSource
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Clock
import javax.inject.Inject
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Positions, marks and what is still to be sent: one profile's, in its folder. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ReadingDataStore

/**
 * Whom the outbox flusher tells what it sent (the screens that show the reading, and the listening that
 * waits), and the life of the profile it sends for.
 */
internal class OutboxListeners @Inject constructor(
    @param:ProfileLife val life: CoroutineScope,
    val changes: ReadingChanges,
    val reading: ReadingWrites,
)

/**
 * Where a flusher of the profile sends: it ends with the profile, so that nothing is sent for one that closed,
 * and nothing in it can fail the profile.
 */
internal fun flusherScope(life: CoroutineScope) = CoroutineScope(
    life.coroutineContext + SupervisorJob(life.coroutineContext[Job]) + Dispatchers.Default +
        CoroutineExceptionHandler { _, _ -> },
)

/**
 * The reading of one profile: its positions, what waits to be sent, and what sends it.
 *
 * Everything here is one profile's for its whole life: the file is in the profile's folder, made with it and
 * deleted with it, and the objects end when it closes. So nothing in these stores names whose it is, and
 * nothing checks it: the account of a profile never changes.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object ReadingProfileModule {

    /** Lets go of its file when the profile closes: the folder can then be deleted, or the profile opened again. */
    @Provides
    @ProfileScoped
    @ReadingDataStore
    fun readingDataStore(folders: ProfileFolders, @ProfileLife life: CoroutineScope): DataStore<Preferences> =
        folders.newDataStore(ReadingFiles.Store, life)

    @Provides
    @ProfileScoped
    fun positionStore(@ReadingDataStore dataStore: DataStore<Preferences>): PositionStore =
        DataStorePositionStore(dataStore)

    /** No reading session queued for a source without them (spec 2c). */
    @Provides
    @ProfileScoped
    fun outbox(@ReadingDataStore dataStore: DataStore<Preferences>, capabilities: SourceCapabilities): Outbox =
        DataStoreOutbox(dataStore).forSource(capabilities)

    /** Next to the positions, under a key of its own. */
    @Provides
    @ProfileScoped
    fun narrationStore(@ReadingDataStore dataStore: DataStore<Preferences>): NarrationStore =
        DataStoreNarrationStore(dataStore)

    /** It lives as long as the profile ([flusherScope]). */
    @Provides
    @ProfileScoped
    fun outboxFlusher(
        outbox: Outbox,
        repository: ReaderRepository,
        wall: Clock,
        listeners: OutboxListeners,
    ): OutboxFlusher = OutboxFlusher(
        outbox = outbox,
        repository = repository,
        scope = flusherScope(listeners.life),
        wall = wall,
        changes = listeners.changes,
        reading = listeners.reading,
    )
}
