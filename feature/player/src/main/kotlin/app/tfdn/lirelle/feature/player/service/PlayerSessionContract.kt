package app.tfdn.lirelle.feature.player.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.SessionCommand
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NextVolume

/**
 * What the playback service and its controllers (the player screen, the mini-player) say to each other,
 * beyond Media3's own player state.
 *
 * **The session's player shows chapters, not files.** Its playlist holds one item per chapter of the book
 * (one for the whole book when the manifest has none): `currentMediaItemIndex` is the chapter,
 * `currentPosition` and `duration` are inside the chapter, so every system surface (notification, lock
 * screen, watch) shows and seeks the chapter. Each chapter's [MediaMetadata]: `title` the book's title,
 * `subtitle` the chapter's display title, `artist` the same with the sleep timer's time left on the current
 * chapter while it runs, `artworkUri` the cover, and [EXTRA_CHAPTER_START_MS] / [EXTRA_CHAPTER_END_MS] in
 * `extras`. The whole-book time is `chapterStartMs(metadata) + currentPosition`.
 *
 * **Player commands**: play, pause, the speed (`setPlaybackSpeed`, kept for the book), `seekTo(chapter,
 * positionInChapter)`, `seekToNextMediaItem` / `seekToPreviousMediaItem` (the start of the next / previous
 * chapter). `seekToNext`, `seekToPrevious`, `seekForward` and `seekBack` skip forward and back by the
 * configured intervals: that is what headsets, steering wheels and watches send for "next" and "previous".
 *
 * **Custom commands** ([PlayerCommand]) carry the rest; **session extras** ([PlayerSessionExtras]) the state
 * a player cannot express.
 *
 * **A narration** (the audio of a read-along EPUB, [ACTION_LOAD_NARRATION]) plays like a book whose chapters
 * are its narrated sections (each item's [EXTRA_CHAPTER_SECTION_INDEX] its section's place in the book's
 * reading order); [EXTRA_NARRATION_FILE_ID] says it is one, [EXTRA_NARRATION_FINISHED] that it
 * reached its end. Its session activity opens the app with [EXTRA_OPEN_READER_BOOK_ID].
 */
object PlayerSessionContract {
    private const val Prefix = "app.tfdn.lirelle.player."

    const val ACTION_BACK = "${Prefix}BACK"
    const val ACTION_FORWARD = "${Prefix}FORWARD"

    /** In the first seconds of a chapter, the previous one; later, the start of the current one. */
    const val ACTION_PREVIOUS_CHAPTER = "${Prefix}PREVIOUS_CHAPTER"
    const val ACTION_NEXT_CHAPTER = "${Prefix}NEXT_CHAPTER"

    /** [ARG_SPEED]: kept for the book, and for the next new one. */
    const val ACTION_SET_SPEED = "${Prefix}SET_SPEED"

    /**
     * The speed after the current one among the speed sheet's shortcuts, the first after the last: the car's
     * speed button, which has one tap to give. Kept for the book as [ACTION_SET_SPEED] is.
     */
    const val ACTION_NEXT_SPEED = "${Prefix}NEXT_SPEED"

    /** [ARG_SLEEP_MINUTES]: 0 is the end of the chapter. */
    const val ACTION_SLEEP_START = "${Prefix}SLEEP_START"
    const val ACTION_SLEEP_EXTEND = "${Prefix}SLEEP_EXTEND"
    const val ACTION_SLEEP_CANCEL = "${Prefix}SLEEP_CANCEL"

    /** [ARG_BOOK_ID], [ARG_FROM_START] (default false), [ARG_PLAY] (default true). */
    const val ACTION_LOAD_BOOK = "${Prefix}LOAD_BOOK"

    /** [ARG_BOOK_TIME_MS]: a place on the whole book's timeline ("Go there", "Go back"). */
    const val ACTION_SEEK_BOOK_TIME = "${Prefix}SEEK_BOOK_TIME"

    /** Loads the next volume of a finished book and plays it. */
    const val ACTION_LISTEN_TO_NEXT = "${Prefix}LISTEN_TO_NEXT"

    /** Stops and forgets the book: the end panel was closed. */
    const val ACTION_CLOSE_BOOK = "${Prefix}CLOSE_BOOK"

    /**
     * The narration of a read-along file: [ARG_BOOK_ID], [ARG_FILE_ID], [ARG_PLAY] (default true); in the
     * section [ARG_SECTION_INDEX] when given, at its sentence [ARG_FRAGMENT] (its element id) or, without one,
     * at its first sentence; where it was left without a section.
     */
    const val ACTION_LOAD_NARRATION = "${Prefix}LOAD_NARRATION"

    /** In the first seconds of a sentence, the previous one; later, the start of the current one. */
    const val ACTION_PREVIOUS_SENTENCE = "${Prefix}PREVIOUS_SENTENCE"
    const val ACTION_NEXT_SENTENCE = "${Prefix}NEXT_SENTENCE"

    const val ARG_SPEED = "speed"
    const val ARG_SLEEP_MINUTES = "sleepMinutes"
    const val ARG_BOOK_ID = "bookId"
    const val ARG_FROM_START = "fromStart"
    const val ARG_PLAY = "play"
    const val ARG_BOOK_TIME_MS = "bookTimeMs"
    const val ARG_FILE_ID = "fileId"
    const val ARG_SECTION_INDEX = "sectionIndex"
    const val ARG_FRAGMENT = "fragment"

    /** Session extras. A missing key is "none" (no book, no sleep timer, not finished…). Book ids by their text. */
    const val EXTRA_BOOK_ID = "bookId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_AUTHORS = "authors"
    const val EXTRA_NARRATORS = "narrators"
    const val EXTRA_COVER_URL = "coverUrl"
    const val EXTRA_ARTWORK_URL = "artworkUrl"
    const val EXTRA_TOTAL_MS = "totalMs"

    /** False when a file's duration is unknown: the book's percentage and time left mean nothing then. */
    const val EXTRA_DURATIONS_KNOWN = "durationsKnown"

    /** The book is in parts, not in chapters: none of its chapters has a title of its own. */
    const val EXTRA_IN_PARTS = "inParts"
    const val EXTRA_LOADING = "loading"
    const val EXTRA_LOAD_FAILED = "loadFailed"

    /** Paused for lack of network; playback resumes by itself when it is back. */
    const val EXTRA_OFFLINE = "offline"

    /** Paused because the server cannot be reached although there is a network; play tries again. */
    const val EXTRA_UNREACHABLE = "unreachable"

    /** 0 for the end of the chapter. */
    const val EXTRA_SLEEP_MINUTES = "sleepMinutes"

    /** Missing for the end of the chapter. */
    const val EXTRA_SLEEP_REMAINING_MS = "sleepRemainingMs"
    const val EXTRA_SLEEP_FADING = "sleepFading"
    const val EXTRA_FINISHED = "finished"
    const val EXTRA_NEXT_BOOK_ID = "nextBookId"
    const val EXTRA_NEXT_TITLE = "nextTitle"
    const val EXTRA_NEXT_SERIES_INDEX = "nextSeriesIndex"

    /** The read-along file whose narration plays, by its id's text; missing for an audiobook. */
    const val EXTRA_NARRATION_FILE_ID = "narrationFileId"

    /** The narration was listened to its end. */
    const val EXTRA_NARRATION_FINISHED = "narrationFinished"

    /** In the intent of a narration's session activity: the book whose reader to open (its id's text). */
    const val EXTRA_OPEN_READER_BOOK_ID = "app.tfdn.lirelle.OPEN_READER_BOOK_ID"

    /**
     * Beside [EXTRA_OPEN_READER_BOOK_ID]: the profile the book is a book of (a String). The same id is another
     * book for another account: the reader is opened only while that profile is the open one.
     */
    const val EXTRA_OPEN_READER_PROFILE_ID = "app.tfdn.lirelle.OPEN_READER_PROFILE_ID"

    /** In a chapter item's [MediaMetadata.extras]: its bounds on the whole book's timeline. */
    const val EXTRA_CHAPTER_START_MS = "chapterStartMs"
    const val EXTRA_CHAPTER_END_MS = "chapterEndMs"

    /** In a chapter item's [MediaMetadata.extras]: the manifest chapter's id (a String), when there is one. */
    const val EXTRA_CHAPTER_ID = "chapterId"

    /** In a narration's chapter item's [MediaMetadata.extras]: its section's place in the book's reading order. */
    const val EXTRA_CHAPTER_SECTION_INDEX = "chapterSectionIndex"

    /** Every custom command the session accepts. */
    val actions: List<String> = listOf(
        ACTION_BACK, ACTION_FORWARD, ACTION_PREVIOUS_CHAPTER, ACTION_NEXT_CHAPTER, ACTION_SET_SPEED,
        ACTION_SLEEP_START, ACTION_SLEEP_EXTEND, ACTION_SLEEP_CANCEL, ACTION_LOAD_BOOK, ACTION_SEEK_BOOK_TIME,
        ACTION_LISTEN_TO_NEXT, ACTION_CLOSE_BOOK, ACTION_LOAD_NARRATION, ACTION_PREVIOUS_SENTENCE,
        ACTION_NEXT_SENTENCE, ACTION_NEXT_SPEED,
    )

    private const val ResumePrefix = "resume/"

    /** Before the id of a book the car starts: no id of the car's own lists, nor of the player's items. */
    private const val StartPrefix = "start/"

    /**
     * The item a play button gets back after the service ended (playback resumption): the last book, as a
     * placeholder that the session's player turns into loading it.
     */
    fun resumeItem(book: LastBook): MediaItem = MediaItem.Builder()
        .setMediaId("$ResumePrefix${book.bookId.value}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(book.title)
                .setArtworkUri(book.coverUrl?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    /**
     * The item the player is set to for a book the car starts: a placeholder, as [resumeItem] is, that the
     * session's player turns into loading the book in the medium listened to last. [title] when the car was
     * listed the book.
     */
    fun startItem(bookId: BookId, title: String?): MediaItem = MediaItem.Builder()
        .setMediaId("$StartPrefix${bookId.value}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsPlayable(true).setIsBrowsable(false).build())
        .build()

    /** The book of a [startItem]; null for another item. */
    fun startedBookId(mediaId: String): BookId? =
        mediaId.takeIf { it.startsWith(StartPrefix) }?.removePrefix(StartPrefix)?.takeIf { it.isNotEmpty() }
            ?.let(::BookId)

    /** The book of a [resumeItem]; null for another item. */
    fun resumedBookId(mediaId: String): BookId? =
        mediaId.takeIf { it.startsWith(ResumePrefix) }?.removePrefix(ResumePrefix)?.takeIf { it.isNotEmpty() }
            ?.let(::BookId)

    /** Where the chapter of [metadata] starts on the whole book's timeline; 0 when it does not say. */
    fun chapterStartMs(metadata: MediaMetadata): Long = metadata.extras?.getLong(EXTRA_CHAPTER_START_MS) ?: 0L
}

/** A custom command of the session, as sent by a controller and handled by the service. */
sealed class PlayerCommand(val action: String) {
    data object Back : PlayerCommand(PlayerSessionContract.ACTION_BACK)

    data object Forward : PlayerCommand(PlayerSessionContract.ACTION_FORWARD)

    data object PreviousChapter : PlayerCommand(PlayerSessionContract.ACTION_PREVIOUS_CHAPTER)

    data object NextChapter : PlayerCommand(PlayerSessionContract.ACTION_NEXT_CHAPTER)

    data class SetSpeed(val speed: Float) : PlayerCommand(PlayerSessionContract.ACTION_SET_SPEED) {
        override fun args() = Bundle().apply { putFloat(PlayerSessionContract.ARG_SPEED, speed) }
    }

    data object NextSpeed : PlayerCommand(PlayerSessionContract.ACTION_NEXT_SPEED)

    data class StartSleep(val choice: SleepChoice) : PlayerCommand(PlayerSessionContract.ACTION_SLEEP_START) {
        override fun args() = Bundle().apply { putInt(PlayerSessionContract.ARG_SLEEP_MINUTES, choice.minutes()) }
    }

    data object ExtendSleep : PlayerCommand(PlayerSessionContract.ACTION_SLEEP_EXTEND)

    data object CancelSleep : PlayerCommand(PlayerSessionContract.ACTION_SLEEP_CANCEL)

    data class LoadBook(val bookId: BookId, val fromStart: Boolean = false, val play: Boolean = true) :
        PlayerCommand(PlayerSessionContract.ACTION_LOAD_BOOK) {
        override fun args() = Bundle().apply {
            // A bundle holds no id: its text.
            putString(PlayerSessionContract.ARG_BOOK_ID, bookId.value)
            putBoolean(PlayerSessionContract.ARG_FROM_START, fromStart)
            putBoolean(PlayerSessionContract.ARG_PLAY, play)
        }
    }

    data class SeekBookTime(val bookTimeMs: Long) : PlayerCommand(PlayerSessionContract.ACTION_SEEK_BOOK_TIME) {
        override fun args() = Bundle().apply { putLong(PlayerSessionContract.ARG_BOOK_TIME_MS, bookTimeMs) }
    }

    data object ListenToNext : PlayerCommand(PlayerSessionContract.ACTION_LISTEN_TO_NEXT)

    data object CloseBook : PlayerCommand(PlayerSessionContract.ACTION_CLOSE_BOOK)

    /**
     * In the section [sectionIndex] when given: at its sentence [fragment], or at its first one without it. Where
     * it was left without a section.
     */
    data class LoadNarration(
        val bookId: BookId,
        val fileId: FileId,
        val sectionIndex: Int? = null,
        val fragment: String? = null,
        val play: Boolean = true,
    ) : PlayerCommand(PlayerSessionContract.ACTION_LOAD_NARRATION) {
        override fun args() = Bundle().apply {
            // A bundle holds no id: its text.
            putString(PlayerSessionContract.ARG_BOOK_ID, bookId.value)
            putString(PlayerSessionContract.ARG_FILE_ID, fileId.value)
            sectionIndex?.let { putInt(PlayerSessionContract.ARG_SECTION_INDEX, it) }
            fragment?.let { putString(PlayerSessionContract.ARG_FRAGMENT, it) }
            putBoolean(PlayerSessionContract.ARG_PLAY, play)
        }

        /** Where the narration starts. */
        val start: NarrationStart
            get() = sectionIndex?.let { NarrationStart.AtSentence(it, fragment.orEmpty()) } ?: NarrationStart.Resume
    }

    data object PreviousSentence : PlayerCommand(PlayerSessionContract.ACTION_PREVIOUS_SENTENCE)

    data object NextSentence : PlayerCommand(PlayerSessionContract.ACTION_NEXT_SENTENCE)

    /** The session command to send. */
    val sessionCommand: SessionCommand get() = SessionCommand(action, Bundle.EMPTY)

    /** The arguments to send with [sessionCommand]. */
    open fun args(): Bundle = Bundle.EMPTY

    companion object {
        private val withoutArgs: Map<String, PlayerCommand> by lazy {
            listOf(
                Back, Forward, PreviousChapter, NextChapter, ExtendSleep, CancelSleep, ListenToNext, CloseBook,
                PreviousSentence, NextSentence, NextSpeed,
            ).associateBy { it.action }
        }

        /** The command [action] with [args] stands for; null for one the session does not know, or without its args. */
        fun of(action: String, args: Bundle): PlayerCommand? = withoutArgs[action] ?: with(PlayerSessionContract) {
            when (action) {
                ACTION_SET_SPEED -> args.floatOrNull(ARG_SPEED)?.let(::SetSpeed)
                ACTION_SLEEP_START -> args.intOrNull(ARG_SLEEP_MINUTES)?.let { StartSleep(sleepChoiceOf(it)) }
                ACTION_LOAD_BOOK -> args.getString(ARG_BOOK_ID)?.let(::BookId)?.let {
                    LoadBook(it, args.getBoolean(ARG_FROM_START, false), args.getBoolean(ARG_PLAY, true))
                }
                ACTION_SEEK_BOOK_TIME -> args.longOrNull(ARG_BOOK_TIME_MS)?.let(::SeekBookTime)
                ACTION_LOAD_NARRATION -> loadNarrationOf(args)
                else -> null
            }
        }
    }
}

private fun loadNarrationOf(args: Bundle): PlayerCommand.LoadNarration? = with(PlayerSessionContract) {
    val bookId = args.getString(ARG_BOOK_ID)?.let(::BookId) ?: return null
    val fileId = args.getString(ARG_FILE_ID)?.let(::FileId) ?: return null
    PlayerCommand.LoadNarration(
        bookId = bookId,
        fileId = fileId,
        sectionIndex = args.intOrNull(ARG_SECTION_INDEX),
        fragment = args.getString(ARG_FRAGMENT),
        play = args.getBoolean(ARG_PLAY, true),
    )
}

/**
 * The book loaded in the service.
 *
 * @property coverUrl the cover in full, for the player screen.
 * @property artworkUrl the cover small, for the mini-player and the like; [coverUrl] unless told.
 * @property inParts none of its chapters has a title of its own: they are shown, and called, parts.
 */
data class SessionBook(
    val id: BookId,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val coverUrl: String?,
    val totalMs: Long,
    val durationsKnown: Boolean = true,
    val artworkUrl: String? = coverUrl,
    val inParts: Boolean = false,
)

/** The book was listened to its end; [nextVolume] is the series' next one with audio, when known. */
data class FinishedBook(val nextVolume: NextVolume?)

/**
 * What the session says beyond its player's state, as its extras.
 *
 * @property narrationFileId the read-along file whose narration plays; null for an audiobook.
 * @property narrationFinished the narration was listened to its end ([finished] is for an audiobook's end).
 */
data class PlayerSessionExtras(
    val book: SessionBook? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val offline: Boolean = false,
    val unreachable: Boolean = false,
    val sleep: SleepState? = null,
    val finished: FinishedBook? = null,
    val narrationFileId: FileId? = null,
    val narrationFinished: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        book?.let(::putBook)
        putBoolean(PlayerSessionContract.EXTRA_LOADING, loading)
        putBoolean(PlayerSessionContract.EXTRA_LOAD_FAILED, loadFailed)
        putBoolean(PlayerSessionContract.EXTRA_OFFLINE, offline)
        putBoolean(PlayerSessionContract.EXTRA_UNREACHABLE, unreachable)
        sleep?.let(::putSleep)
        finished?.let(::putFinished)
        narrationFileId?.let { putString(PlayerSessionContract.EXTRA_NARRATION_FILE_ID, it.value) }
        putBoolean(PlayerSessionContract.EXTRA_NARRATION_FINISHED, narrationFinished)
    }

    companion object {
        fun from(bundle: Bundle): PlayerSessionExtras = with(PlayerSessionContract) {
            PlayerSessionExtras(
                book = bundle.getString(EXTRA_BOOK_ID)?.let(::BookId)?.let {
                    SessionBook(
                        id = it,
                        title = bundle.getString(EXTRA_TITLE).orEmpty(),
                        authors = bundle.getStringArray(EXTRA_AUTHORS)?.toList().orEmpty(),
                        narrators = bundle.getStringArray(EXTRA_NARRATORS)?.toList().orEmpty(),
                        coverUrl = bundle.getString(EXTRA_COVER_URL),
                        totalMs = bundle.getLong(EXTRA_TOTAL_MS),
                        durationsKnown = bundle.getBoolean(EXTRA_DURATIONS_KNOWN, true),
                        artworkUrl = bundle.getString(EXTRA_ARTWORK_URL, bundle.getString(EXTRA_COVER_URL)),
                        inParts = bundle.getBoolean(EXTRA_IN_PARTS),
                    )
                },
                loading = bundle.getBoolean(EXTRA_LOADING),
                loadFailed = bundle.getBoolean(EXTRA_LOAD_FAILED),
                offline = bundle.getBoolean(EXTRA_OFFLINE),
                unreachable = bundle.getBoolean(EXTRA_UNREACHABLE),
                sleep = bundle.intOrNull(EXTRA_SLEEP_MINUTES)?.let {
                    val remaining = bundle.longOrNull(EXTRA_SLEEP_REMAINING_MS)
                    SleepState(sleepChoiceOf(it), remaining, bundle.getBoolean(EXTRA_SLEEP_FADING))
                },
                finished = if (bundle.getBoolean(EXTRA_FINISHED)) FinishedBook(nextVolume(bundle)) else null,
                narrationFileId = bundle.getString(EXTRA_NARRATION_FILE_ID)?.let(::FileId),
                narrationFinished = bundle.getBoolean(EXTRA_NARRATION_FINISHED),
            )
        }

        private fun nextVolume(bundle: Bundle): NextVolume? = with(PlayerSessionContract) {
            bundle.getString(EXTRA_NEXT_BOOK_ID)?.let(::BookId)?.let {
                NextVolume(it, bundle.getString(EXTRA_NEXT_TITLE), bundle.getString(EXTRA_NEXT_SERIES_INDEX))
            }
        }
    }
}

private fun Bundle.putBook(book: SessionBook) = with(PlayerSessionContract) {
    putString(EXTRA_BOOK_ID, book.id.value)
    putString(EXTRA_TITLE, book.title)
    putStringArray(EXTRA_AUTHORS, book.authors.toTypedArray())
    putStringArray(EXTRA_NARRATORS, book.narrators.toTypedArray())
    putString(EXTRA_COVER_URL, book.coverUrl)
    putString(EXTRA_ARTWORK_URL, book.artworkUrl)
    putLong(EXTRA_TOTAL_MS, book.totalMs)
    putBoolean(EXTRA_DURATIONS_KNOWN, book.durationsKnown)
    putBoolean(EXTRA_IN_PARTS, book.inParts)
}

private fun Bundle.putSleep(sleep: SleepState) = with(PlayerSessionContract) {
    putInt(EXTRA_SLEEP_MINUTES, sleep.choice.minutes())
    sleep.remainingMs?.let { putLong(EXTRA_SLEEP_REMAINING_MS, it) }
    putBoolean(EXTRA_SLEEP_FADING, sleep.fading)
}

private fun Bundle.putFinished(finished: FinishedBook) = with(PlayerSessionContract) {
    putBoolean(EXTRA_FINISHED, true)
    finished.nextVolume?.let {
        putString(EXTRA_NEXT_BOOK_ID, it.bookId.value)
        putString(EXTRA_NEXT_TITLE, it.title)
        putString(EXTRA_NEXT_SERIES_INDEX, it.seriesIndex)
    }
}

private fun SleepChoice.minutes(): Int = when (this) {
    is SleepChoice.Minutes -> minutes
    SleepChoice.EndOfChapter -> 0
}

private fun sleepChoiceOf(minutes: Int): SleepChoice =
    if (minutes <= 0) SleepChoice.EndOfChapter else SleepChoice.Minutes(minutes)

private fun Bundle.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null

private fun Bundle.intOrNull(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.floatOrNull(key: String): Float? = if (containsKey(key)) getFloat(key) else null
