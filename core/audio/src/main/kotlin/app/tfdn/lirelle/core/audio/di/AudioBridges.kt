package app.tfdn.lirelle.core.audio.di

import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkQueue
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.audio.position.ListeningSessionQueue
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/** The listening of the open profile, for what Hilt cannot inject it into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface AudioProfileEntry {
    fun audioPlaybackStore(): PlaybackStore

    fun audioPlaybackOutbox(): PlaybackOutbox

    fun audioListeningSessions(): ListeningSessionQueue

    fun audioBookmarkQueue(): BookmarkQueue

    fun audioBookmarks(): AudioBookmarks

    fun audioReadAlongMaps(): ReadAlongMapStore
}

/**
 * Gives view models the listening of the open profile. Unscoped, and in no component that outlives a profile:
 * each view model gets that of the profile open when it is made.
 *
 * The playback service has no such bridge: it takes everything it is made of from one open profile, at once.
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object AudioBridges {

    @Provides
    fun playbackStore(current: CurrentProfile): PlaybackStore = current.entry<AudioProfileEntry>().audioPlaybackStore()

    @Provides
    fun playbackOutbox(current: CurrentProfile): PlaybackOutbox =
        current.entry<AudioProfileEntry>().audioPlaybackOutbox()

    @Provides
    fun listeningSessions(current: CurrentProfile): ListeningSessionQueue =
        current.entry<AudioProfileEntry>().audioListeningSessions()

    @Provides
    fun bookmarkQueue(current: CurrentProfile): BookmarkQueue = current.entry<AudioProfileEntry>().audioBookmarkQueue()

    @Provides
    fun bookmarks(current: CurrentProfile): AudioBookmarks = current.entry<AudioProfileEntry>().audioBookmarks()

    @Provides
    fun readAlongMaps(current: CurrentProfile): ReadAlongMapStore =
        current.entry<AudioProfileEntry>().audioReadAlongMaps()
}
