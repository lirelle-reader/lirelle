package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.downloads.DeviceCopyActions
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkActions

/** The sleep timer's commands, an audiobook's or a narration's. */
interface SleepControls {
    fun startSleep(choice: SleepChoice)

    fun cancelSleep()
}

/** The controls of the player: playing, skips and chapters, the chapter bar, the speed and the sleep timer. */
interface PlaybackControls : SleepControls {
    fun playPause()

    /** Back by the configured interval (15 s by default). */
    fun back()

    /** Forward by the configured interval (30 s by default). */
    fun forward()

    /** In the first seconds of a chapter, the previous one; later, the start of the current one. */
    fun previousChapter()

    fun nextChapter()

    /** [fraction] of the way through the current chapter, from 0 to 1; nothing for a chapter of unknown length. */
    fun seekInChapter(fraction: Float)

    /** Kept for the book, and for the next new one. */
    fun setSpeed(speed: Float)

    /**
     * "Stop listening": the book is closed, its position and its listening saved first, and the service
     * ends; the mini-player and the notification go away.
     */
    fun stopListening()
}

/** A chapter chosen in the list, and "Go back" to where the user was, as in the EPUB reader. */
interface ChapterJumps {
    /** The start of the chapter [index]; "Go back" returns to where playback was. */
    fun goToChapter(index: Int)

    fun goBack()
}

/** The banner of a newer position from elsewhere (spec 8.3). */
interface NewerPositionActions {
    /** "Go there": the player moves to it, and "Go back" returns to where it was. */
    fun acceptNewerPosition()

    /** "Ignore": the player stays; its next write wins on the server. */
    fun ignoreNewerPosition()
}

/**
 * The end panel of a book listened to its end (spec 10), with its row for what the device keeps of the book
 * ([DeviceCopyActions]).
 */
interface EndPanelActions : DeviceCopyActions {
    /** Null clears the stars. Sent when the panel is closed. */
    fun setRating(stars: Int?)

    /** "Close": the stars are sent, the book is closed and the screen leaves. */
    fun closeEndPanel()

    /** The grip, a swipe down, a tap above or back: the stars are sent, the finished book stays. */
    fun dismissEndPanel()

    /** The panel again, after it was dismissed ("Rate" on the mini-player). */
    fun openEndPanel()

    /** The stars are sent, then the series' next volume is loaded and played. */
    fun listenToNext()
}

/** The player settings changed from the player screen itself. */
interface PlayerSettingsActions {
    /** "Shake to extend" in the sleep timer's sheet. */
    fun setShakeToExtend(enabled: Boolean)
}

/** A book that could not be loaded. */
interface LoadActions {
    /** The last book asked for is loaded again, as it was asked (from its position or its start). */
    fun retryLoad()
}

/** What the user does on the player screen. */
interface PlayerActions :
    PlaybackControls,
    ChapterJumps,
    NewerPositionActions,
    EndPanelActions,
    PlayerSettingsActions,
    LoadActions,
    BookmarkActions
