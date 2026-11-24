package app.tfdn.lirelle.core.audio.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.position.DataStoreListeningSessionQueue
import app.tfdn.lirelle.core.audio.position.DataStorePlaybackOutbox
import app.tfdn.lirelle.core.audio.position.DataStorePlaybackStore
import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.ListeningSessionQueue
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.PlaybackSyncEvents
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.audio.position.ReadingBaselines
import app.tfdn.lirelle.core.audio.position.forSource
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.sync.ReadingWrites
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Lazy
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

/** Listening positions, known versions and what is still to be sent: one profile's, in its folder. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class ListeningDataStore

/**
 * Whom the playback flusher tells what the server answered (the screens that show the reading, and the
 * player), the listening sessions it declares after the positions, the clock it spaces its tellings by, the
 * reading its positions wait behind, and the life of the profile it sends for.
 */
internal class FlusherListeners @Inject constructor(
    @param:ProfileLife val life: CoroutineScope,
    val changes: ReadingChanges,
    val events: PlaybackSyncEvents,
    val sessions: ListeningSessionQueue,
    val conflicts: ListeningConflicts,
    val baselines: ReadingBaselines,
    val clock: MonotonicClock,
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
 * The listening of one profile: its positions, what waits to be sent, and what sends it.
 *
 * Everything here is one profile's for its whole life: the file is in the profile's folder, made with it and
 * deleted with it, and the objects end when it closes. So nothing in these stores names whose it is, and
 * nothing checks it: the account of a profile never changes.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object AudioCoreModule {

    /** Lets go of its file when the profile closes: the folder can then be deleted, or the profile opened again. */
    @Provides
    @ProfileScoped
    @ListeningDataStore
    fun listeningDataStore(folders: ProfileFolders, @ProfileLife life: CoroutineScope): DataStore<Preferences> =
        folders.newDataStore(ListeningFiles.Store, life)

    @Provides
    @ProfileScoped
    fun playbackStore(@ListeningDataStore dataStore: DataStore<Preferences>): PlaybackStore =
        DataStorePlaybackStore(dataStore)

    @Provides
    @ProfileScoped
    fun playbackOutbox(@ListeningDataStore dataStore: DataStore<Preferences>): PlaybackOutbox =
        DataStorePlaybackOutbox(dataStore)

    /** No listening session queued for a source without reading sessions (spec 2c). */
    @Provides
    @ProfileScoped
    fun listeningSessionQueue(
        @ListeningDataStore dataStore: DataStore<Preferences>,
        capabilities: SourceCapabilities,
    ): ListeningSessionQueue = DataStoreListeningSessionQueue(dataStore).forSource(capabilities)

    /** It lives as long as the profile ([flusherScope]). */
    @Provides
    @ProfileScoped
    fun playbackFlusher(
        outbox: PlaybackOutbox,
        store: PlaybackStore,
        repository: AudioRepository,
        listeners: FlusherListeners,
    ): PlaybackFlusher = PlaybackFlusher(
        outbox = outbox,
        store = store,
        repository = repository,
        scope = flusherScope(listeners.life),
        changes = listeners.changes,
        onManifestChanged = listeners.events::manifestChanged,
    ).also {
        it.clock = listeners.clock
        it.sessions = listeners.sessions
        it.writes = listeners.conflicts
        it.baselines = listeners.baselines
        it.reading = listeners.reading
        listeners.reading.onSent = it::kick
    }

    /** What stands against this device's writes, for as long as the profile is open. */
    @Provides
    @ProfileScoped
    fun listeningConflicts(
        store: PlaybackStore,
        outbox: PlaybackOutbox,
        clock: Clock,
        flusher: Lazy<PlaybackFlusher>,
    ): ListeningConflicts = ListeningConflicts(store, outbox, clock) { flusher.get().kick() }

    /** One per player: it remembers when it last sent. */
    @Provides
    fun positionWriter(
        store: PlaybackStore,
        outbox: PlaybackOutbox,
        flusher: PlaybackFlusher,
        clock: MonotonicClock,
    ): PositionWriter = PositionWriter(store, outbox, flusher, clock)
}
