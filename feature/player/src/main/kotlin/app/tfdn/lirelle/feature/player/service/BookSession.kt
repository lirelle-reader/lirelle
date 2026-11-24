package app.tfdn.lirelle.feature.player.service

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.audio.settings.toValidSpeed
import app.tfdn.lirelle.core.audio.timer.ResumeRewind
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.NextVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MillisPerSecond = 1_000L
private const val Tag = "BookSession"

/**
 * The book whose listening [next] taking its place ends: this one when it is finished and [next] is another
 * book (or the same one in another medium), or it again [fromStart]. Null otherwise.
 */
private fun LoadedBook?.listeningEndedBy(next: LoadedBook, fromStart: Boolean, extras: PlayerSessionExtras): BookId? =
    this?.bookId?.takeIf {
        extras.finished != null && (it != next.bookId || fromStart || !source.sameMedium(next.source))
    }

/**
 * This is what [request] asks for (the same book, and its audiobook or the narration of the same file),
 * nothing is being loaded and the last load did not fail (a retry loads again), it is not at its end, and the
 * engine holds it ready (or filling its buffer), not stopped by an error: it can play on where it is.
 */
private fun LoadedBook?.canPlayOn(request: LoadRequest, loading: Job?, extras: PlayerSessionExtras, engine: Player) =
    this != null && bookId == request.bookId && request.isFor(source) && loading?.isActive != true &&
        !extras.loadFailed && extras.finished == null && !extras.narrationFinished && engine.playerError == null &&
        (engine.playbackState == Player.STATE_READY || engine.playbackState == Player.STATE_BUFFERING)

/**
 * What [request] asks for, read; null when it cannot be (a narration without [narrations] cannot), and when
 * reading it broke (a map or a store that cannot be read): the load failed, it is not left loading.
 */
private suspend fun fetch(request: LoadRequest, loader: BookLoader, narrations: NarrationParts?): LoadedBook? {
    val outcome = runCatching {
        when (request) {
            is LoadRequest.Audiobook -> loader.load(request.bookId, request.fromStart, request.at)
            is LoadRequest.Narration -> narrations?.loader?.load(request.bookId, request.fileId, request.start)
        }
    }
    outcome.exceptionOrNull()?.let { failure ->
        if (failure is CancellationException) throw failure
        Log.w(Tag, "What was asked could not be read", failure)
    }
    return outcome.getOrNull()
}

/** The same book in the same medium (its audiobook, or the narration of the same file): its files changed. */
private fun LoadedBook?.isSameAs(other: LoadedBook): Boolean =
    this != null && bookId == other.bookId && source.sameMedium(other.source)

/**
 * Where a narration takes up again, [nowMs] being where it is held: stopped by the sleep timer at the end of a
 * section ([endOfSection]), at the start of the section that follows, not in the last sentence again; stopped by
 * a timed sleep, at the start of the sentence holding the timer's resume point; [paused], at the start of the
 * sentence it paused in. Null: it plays on where it is.
 */
private fun ReadAlongMap.resumeAt(timerPoint: Long?, endOfSection: Boolean, paused: Boolean, nowMs: Long?): Long? =
    when {
        timerPoint != null && endOfSection -> nowMs?.let(::sectionEndNear)
        else -> (timerPoint ?: nowMs?.takeIf { paused })?.let(::sentenceStartAt)
    }

/** What the session says of [book] once it is opened: nothing loading, failed nor finished. */
private fun extrasOf(book: LoadedBook): PlayerSessionExtras {
    val manifest = book.playlist.manifest
    val timeline = book.playlist.timeline
    return PlayerSessionExtras(
        book = SessionBook(
            book.bookId, manifest.title, manifest.authors, manifest.narrators, book.playlist.coverUrl,
            timeline.totalMs, timeline.durationsKnown, book.playlist.artworkUrl, book.playlist.inParts,
        ),
        narrationFileId = book.source.narrationFileId,
    )
}

/**
 * Loads the audiobook [bookId] and replaces the current book with it once it is read (a failed load keeps it).
 * [at] places it at a book time: the same book again after its files changed on the server. [playing] says,
 * once it is loaded, whether to play (a pause pressed meanwhile).
 *
 * The book already held, ready and neither at its end nor in error, is not loaded again unless asked
 * [fromStart]: that would fill its buffer again and take it back to its stored position. It plays on
 * from where it is, as the play button would have it.
 */
fun BookSession.load(
    bookId: BookId,
    fromStart: Boolean,
    play: Boolean,
    at: Long? = null,
    playing: () -> Boolean = { play },
): Job = load(LoadRequest.Audiobook(bookId, fromStart, at), play, playing)

/**
 * The book loaded in the service, and what the commands do to it. Call it on the main thread.
 *
 * One book at a time: loading another one leaves the current one first ([leaving]: its position and its
 * listening session go out). Play resumes a little before where it paused (a few seconds more the longer the
 * pause), or from before the fade after the sleep timer stopped it.
 *
 * A book is its audiobook or the narration of one of its read-along files ([BookSource]); either replaces the
 * other. A narration steps by sentence besides by seconds, resumes at the start of the sentence it paused in
 * (or held the sleep timer's resume point), keeps its own speed, and at its end looks for no next volume.
 *
 * @param nextVolume the series' next volume with audio, asked when a book ends.
 */
@OptIn(UnstableApi::class)
class BookSession(
    private val engine: Player,
    private val navigator: BookNavigator,
    private val loader: BookLoader,
    private val sleep: SleepDriver,
    private val timer: SleepTimer,
    private val settingsStore: PlayerSettingsStore,
    private val nextVolume: suspend (seriesId: SeriesId, bookId: BookId) -> NextVolume?,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) : ChapterPlayer.Actions {
    private val mutableExtras = MutableStateFlow(PlayerSessionExtras())

    /** The book, loading, a failed load, finished; the sleep timer and the network are added by the service. */
    val extras: StateFlow<PlayerSessionExtras> = mutableExtras.asStateFlow()

    /** Called before the loaded book is left. */
    var leaving: () -> Unit = {}

    /**
     * The listening of the finished book [bookId] is over: it is left for another one ("Listen to the next
     * one", from the notification or the app), listened to again from its start ("Listen again"), or
     * closed. Its next listening is a new one.
     */
    var finishedLeft: (bookId: BookId) -> Unit = {}

    /** A book was opened (the last book, for playback resumption). */
    var opened: (LoadedBook) -> Unit = {}

    /** The book was closed. */
    var closed: () -> Unit = {}

    /** The only book a play button may resume: the profile's last one. */
    var resumable: suspend () -> BookId? = { null }

    /** How a resumption ended. */
    var resumed: (ResumeOutcome) -> Unit = {}

    /** How narrations are loaded, and the cursor told of their end; without it, a narration does not load. */
    var narrations: NarrationParts? = null

    var settings = PlayerSettings()
        private set

    private var current: LoadedBook? = null

    /** Whether the book loaded plays from its download: known before the engine reads its first file. */
    val playsDownload: Boolean get() = current?.kept == true

    /** Whether a book is loaded. */
    val hasBook: Boolean get() = current != null
    private var loading: Job? = null
    private var pausedAt: Long? = null

    /** The sleep timer last started: what its stop was. */
    private var sleepChoice: SleepChoice? = null

    /** The map of the narration loaded; null for an audiobook. */
    private val narrationMap: ReadAlongMap? get() = (current?.source as? BookSource.Narration)?.map

    /** The commands that take no arguments: each one listed in [handle] too. */
    private val plainCommands: Map<PlayerCommand, () -> Unit> = mapOf(
        PlayerCommand.Back to ::back,
        PlayerCommand.Forward to ::forward,
        PlayerCommand.PreviousChapter to navigator::previousChapter,
        PlayerCommand.NextChapter to navigator::nextChapter,
        PlayerCommand.PreviousSentence to { narrationMap?.let(navigator::previousSentence) },
        PlayerCommand.NextSentence to { narrationMap?.let(navigator::nextSentence) },
        PlayerCommand.ExtendSleep to sleep::extend,
        PlayerCommand.CancelSleep to sleep::cancel,
        PlayerCommand.ListenToNext to { extras.value.finished?.nextVolume?.let { load(it.bookId, false, true) } },
        PlayerCommand.CloseBook to ::close,
    )

    init {
        scope.launch { settingsStore.settings.collect { settings = it } }
        engine.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                // Moved away from the end (a seek, a chapter, a place): the book is no longer finished.
                if (extras.value.narrationFinished) narrations?.cursor?.finish(null)
                mutableExtras.update { it.copy(finished = null, narrationFinished = false) }
                // The user chose a place while paused: play starts there, not from before the pause.
                if (!engine.playWhenReady) {
                    timer.consumeResumePoint()
                    pausedAt = null
                }
            }
        })
    }

    fun handle(command: PlayerCommand) {
        when (command) {
            PlayerCommand.Back, PlayerCommand.Forward, PlayerCommand.PreviousChapter, PlayerCommand.NextChapter,
            PlayerCommand.PreviousSentence, PlayerCommand.NextSentence, PlayerCommand.ExtendSleep,
            PlayerCommand.CancelSleep, PlayerCommand.ListenToNext, PlayerCommand.CloseBook,
            -> plainCommands.getValue(command)()
            is PlayerCommand.SetSpeed -> setSpeed(command.speed)
            is PlayerCommand.StartSleep -> {
                sleepChoice = command.choice
                sleep.start(command.choice, settings.sleepFade)
                scope.launch { settingsStore.update { it.copy(lastSleepChoice = command.choice) } }
            }
            is PlayerCommand.LoadBook -> load(command.bookId, command.fromStart, command.play)
            is PlayerCommand.LoadNarration ->
                load(LoadRequest.Narration(command.bookId, command.fileId, command.start), command.play)
            is PlayerCommand.SeekBookTime -> navigator.seekBookTime(command.bookTimeMs)
        }
    }

    override fun play() {
        if (current == null) return
        if (engine.playbackState == Player.STATE_IDLE) engine.prepare()
        val paused = pausedAt
        val timerPoint = timer.consumeResumePoint()
        val map = narrationMap
        if (map != null && engine.playbackState == Player.STATE_ENDED) {
            // An ended narration starts over from its first sentence, as "Listen again" does an audiobook.
            navigator.seekBookTime((map.sentenceAt(0) ?: map.nextSentence(0))?.startMs ?: 0)
            pausedAt = null
            engine.play()
            return
        }
        val resumeAt = if (map != null) {
            val endOfSection = sleepChoice == SleepChoice.EndOfChapter
            map.resumeAt(timerPoint, endOfSection, paused != null, navigator.bookTimeMs())
        } else {
            timerPoint ?: paused?.let { since ->
                val pausedFor = clock.nowMillis() - since
                navigator.bookTimeMs()?.let { ResumeRewind.resumeAt(it, pausedFor, settings.rewindOnResume) }
            }
        }
        resumeAt?.let(navigator::seekBookTime)
        pausedAt = null
        engine.play()
    }

    override fun forward() = navigator.skip(settings.forwardSeconds * MillisPerSecond)

    override fun back() = navigator.skip(-settings.backSeconds * MillisPerSecond)

    override fun resume(bookId: BookId, play: () -> Boolean, done: () -> Unit) {
        scope.launch {
            // A store that cannot be read rejects the book: done always comes.
            val allowed = runCatching { resumable() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (allowed != bookId) {
                done()
                resumed(ResumeOutcome.Rejected)
                return@launch
            }
            load(bookId, fromStart = false, play = true, playing = play).invokeOnCompletion { cause ->
                done()
                resumed(
                    when {
                        cause is CancellationException -> ResumeOutcome.Cancelled
                        current?.bookId == bookId -> ResumeOutcome.Loaded
                        else -> ResumeOutcome.Rejected
                    },
                )
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val book = current ?: return
        val valid = speed.toValidSpeed()
        engine.setPlaybackSpeed(valid)
        scope.launch {
            // A narration keeps a speed of its own for the book.
            if (book.source is BookSource.Narration) {
                settingsStore.setNarrationSpeed(book.bookId, valid)
            } else {
                settingsStore.setSpeed(book.bookId, valid)
            }
        }
    }

    /** Playback was paused (not stalled): the next play rewinds by how long it lasted. */
    fun onPaused() {
        pausedAt = clock.nowMillis()
    }

    /**
     * The last file ended: finished, and the next volume is looked for. A narration has none: it is finished,
     * and the reader is told.
     */
    fun onFinished() {
        val book = current ?: return
        if (book.source is BookSource.Narration) {
            mutableExtras.update { it.copy(narrationFinished = true) }
            narrations?.cursor?.finish(book.bookId)
            return
        }
        mutableExtras.update { it.copy(finished = FinishedBook(null)) }
        val series = book.seriesId ?: return
        scope.launch {
            val next = nextVolume(series, book.bookId) ?: return@launch
            if (current === book) mutableExtras.update { it.copy(finished = it.finished?.copy(nextVolume = next)) }
        }
    }

    /**
     * Loads what [request] asks for and replaces the current book with it once it is read (a failed load keeps
     * it); [playing] says, once it is loaded, whether to play (a pause pressed meanwhile).
     *
     * What is already held, ready and neither at its end nor in error, is not loaded again (unless asked from
     * its start, or at a place): it plays on from where it is, as the play button would have it; a narration
     * asked at a sentence goes there.
     */
    fun load(request: LoadRequest, play: Boolean, playing: () -> Boolean = { play }): Job {
        if (request.mayPlayOn && current.canPlayOn(request, loading, extras.value, engine)) {
            // The sentence first: a place chosen while paused is where play starts.
            request.sentence?.let { narrationMap?.timeOf(it) }?.let(navigator::seekBookTime)
            if (playing() && !engine.playWhenReady) play()
            // Played on: no failure stands for it any more.
            mutableExtras.update { it.copy(loadFailed = false) }
            return Job().apply { complete() }
        }
        loading?.cancel()
        mutableExtras.update { it.copy(loading = true, loadFailed = false) }
        return scope.launch {
            val book = fetch(request, loader, narrations)
            if (book == null) {
                mutableExtras.update { it.copy(loading = false, loadFailed = true) }
                return@launch
            }
            val previous = current
            val sameBook = previous.isSameAs(book)
            // The same book in its changed files goes on: no position with the old files, no new session.
            if (previous != null && !sameBook) leaving()
            // Another book, or the same one from its start, after its end: that listening is over.
            previous.listeningEndedBy(book, request.fromStart, extras.value)?.let(finishedLeft)
            if (!sameBook) sleep.cancel()
            val playNow = playing()
            // Paused before the new files come: the pause belongs to the book left, not to this one.
            if (!playNow) engine.playWhenReady = false
            current = book
            pausedAt = null
            navigator.open(book.playlist, book.startMs)
            engine.setPlaybackSpeed(book.speed)
            engine.prepare()
            engine.playWhenReady = playNow
            mutableExtras.value = extrasOf(book)
            opened(book)
        }.also { loading = it }
    }

    private fun close() {
        loading?.cancel()
        current?.let { book ->
            leaving()
            if (extras.value.finished != null) finishedLeft(book.bookId)
        }
        sleep.cancel()
        current = null
        pausedAt = null
        engine.stop()
        navigator.clear()
        mutableExtras.value = PlayerSessionExtras()
        closed()
    }
}
