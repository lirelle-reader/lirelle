package app.tfdn.lirelle.core.audio.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkFlusher
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkQueue
import app.tfdn.lirelle.core.audio.bookmarks.DataStoreBookmarkQueue
import app.tfdn.lirelle.core.audio.bookmarks.DefaultAudioBookmarks
import app.tfdn.lirelle.core.audio.bookmarks.forSource
import app.tfdn.lirelle.core.library.AudioBookmarkRepository
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import java.time.Clock
import kotlinx.coroutines.CoroutineScope

/** The audiobook bookmarks of one profile: as its listening ([AudioCoreModule]), its own for its whole life. */
@Module
@InstallIn(ProfileComponent::class)
internal object AudioBookmarksModule {

    /**
     * In the profile's listening file, under a key of its own: gone with the profile's folder. Nothing is queued
     * for a source without audio bookmarks (spec 2c).
     */
    @Provides
    @ProfileScoped
    fun bookmarkQueue(
        @ListeningDataStore dataStore: DataStore<Preferences>,
        capabilities: SourceCapabilities,
    ): BookmarkQueue = DataStoreBookmarkQueue(dataStore).forSource(capabilities)

    /** It lives as long as the profile ([flusherScope]). */
    @Provides
    @ProfileScoped
    fun bookmarkFlusher(
        queue: BookmarkQueue,
        repository: AudioBookmarkRepository,
        @ProfileLife life: CoroutineScope,
    ): BookmarkFlusher = BookmarkFlusher(
        queue = queue,
        repository = repository,
        scope = flusherScope(life),
    )

    @Provides
    @ProfileScoped
    fun audioBookmarks(queue: BookmarkQueue, flusher: BookmarkFlusher, clock: Clock): AudioBookmarks =
        DefaultAudioBookmarks(queue, flusher, clock)
}
