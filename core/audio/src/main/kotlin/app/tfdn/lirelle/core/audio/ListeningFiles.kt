package app.tfdn.lirelle.core.audio

/**
 * The names a profile's listening is kept under, in its folders. An installation from before the profiles
 * kept it under the same names at the root of the application's own folders: the migration moves it by these,
 * so that it cannot look for a name the stores do not use.
 */
object ListeningFiles {
    /** The preferences file of the places listened to, the bookmarks and what waits to be sent. */
    const val Store = "listening"

    /** The folder of the maps of the books read along with their narration. */
    const val ReadAlong = "readalong"

    /** The folder, in the cache, of the audio streamed. */
    const val Cache = "audio"

    /**
     * The database an installation from before the profiles kept the index of its one audio cache in: Media3's
     * own, for the whole application. A profile's cache has its index in a file beside it instead, so the old
     * cache cannot follow: it is put away to be deleted, and this index, which names what was listened to, is
     * deleted with it.
     */
    const val OldCacheIndex = "exoplayer_internal.db"

    /** The device's preferences file of the player settings, which held the speed of each book too. */
    const val PlayerSettings = "player_settings"

    /** The profile's preferences file of the speed of each of its books. */
    const val BookSpeeds = "book_speeds"
}
