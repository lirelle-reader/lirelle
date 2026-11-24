package app.tfdn.lirelle.core.reader.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.reader.marks.DataStoreMarks
import app.tfdn.lirelle.core.reader.marks.DefaultMarks
import app.tfdn.lirelle.core.reader.marks.MarkOutbox
import app.tfdn.lirelle.core.reader.marks.Marks
import app.tfdn.lirelle.core.reader.marks.MarksFlusher
import app.tfdn.lirelle.core.reader.marks.MarksRefresher
import app.tfdn.lirelle.core.reader.marks.MarksStore
import app.tfdn.lirelle.core.reader.marks.forSource
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** Whom the marks flusher tells what it sent, and the life of the profile it sends for. */
internal class MarksListeners @Inject constructor(
    @param:ProfileLife val life: CoroutineScope,
    val refresher: MarksRefresher,
)

/**
 * Bookmarks and highlights, in the reading data store: one profile's for their whole life, as everything
 * in that file is ([ReadingProfileModule]), and gone with its folder. Nothing in them names an account.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object MarksModule {

    /** The store and the outbox are one state: one instance serves both. */
    @Provides
    @ProfileScoped
    fun marks(@ReadingDataStore dataStore: DataStore<Preferences>, wall: Clock): DataStoreMarks =
        DataStoreMarks(dataStore, wall)

    @Provides
    fun marksStore(marks: DataStoreMarks): MarksStore = marks

    /**
     * What the screens record changes through: nothing is shown nor queued for a source without annotations
     * (spec 2c). The flusher works on [DataStoreMarks] itself, which then holds nothing to send.
     */
    @Provides
    fun markOutbox(marks: DataStoreMarks, capabilities: SourceCapabilities): MarkOutbox = marks.forSource(capabilities)

    /** One per profile: its throttle and its one-at-a-time rule hold for every screen. */
    @Provides
    @ProfileScoped
    fun marksRefresher(
        store: MarksStore,
        repository: MarksRepository,
        monotonic: MonotonicClock,
    ): MarksRefresher = MarksRefresher(store, repository, monotonic)

    /**
     * It lives as long as the profile ([flusherScope]). Once a book's changes went, the refresher reads its
     * lists again.
     */
    @Provides
    @ProfileScoped
    fun marksFlusher(
        marks: DataStoreMarks,
        repository: MarksRepository,
        wall: Clock,
        listeners: MarksListeners,
    ): MarksFlusher = MarksFlusher(
        outbox = marks,
        store = marks,
        repository = repository,
        scope = flusherScope(listeners.life),
        wall = wall,
        sent = listeners.refresher,
    )

    @Provides
    @ProfileScoped
    fun marksFacade(
        marks: DataStoreMarks,
        outbox: MarkOutbox,
        refresher: MarksRefresher,
        flusher: MarksFlusher,
    ): Marks = DefaultMarks(outbox = outbox, store = marks, refresher = refresher, flusher = flusher)
}
