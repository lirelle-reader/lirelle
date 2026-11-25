@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.narration.NarrationCursor
import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.ListeningSessionSender
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.position.PlaybackSyncEvents
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.audio.position.ReadingBaselines
import app.tfdn.lirelle.core.audio.session.ListeningSessionClockFactory
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.audio.timer.SleepTimer
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.library.offline.DownloadedFiles
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.OpenEntry
import app.tfdn.lirelle.core.session.profile.openEntryOrNull
import app.tfdn.lirelle.feature.player.car.CarAsks
import app.tfdn.lirelle.feature.player.car.CarBrowser
import app.tfdn.lirelle.feature.player.car.CarCovers
import app.tfdn.lirelle.feature.player.car.CarIds
import app.tfdn.lirelle.feature.player.car.CarItems
import app.tfdn.lirelle.feature.player.car.CarLibrary
import app.tfdn.lirelle.feature.player.car.CarStart
import app.tfdn.lirelle.feature.player.car.NoticeAsError
import app.tfdn.lirelle.feature.player.car.PlayingCover
import app.tfdn.lirelle.feature.player.car.refreshCars
import app.tfdn.lirelle.feature.player.di.MediaClient
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import dagger.hilt.android.AndroidEntryPoint
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** The clocks of the service: ticks and timers, the time of a position, listening sessions. */
class PlaybackClocks @Inject constructor(
    val monotonic: MonotonicClock,
    val wall: Clock,
    val sessions: ListeningSessionClockFactory,
)

/**
 * Where the audio comes from: the files the downloads keep, else the cache, then the server with the
 * authenticated client, on connections of its own.
 */
class MediaStreams @Inject constructor(
    val cache: AudioCache,
    @param:MediaClient val client: OkHttpClient,
    /** The pages' client, for the session's covers: on the audio's connections, they would wait behind it. */
    @param:AuthenticatedClient val coverClient: OkHttpClient,
    val downloaded: DownloadedFiles,
)

/** Where listening goes: the position (stored, sent every 5 s) and the listening sessions, both queued. */
class ListeningOutput @Inject constructor(val writer: PositionWriter, val sessions: ListeningSessionSender)

/**
 * Where books come from: the server, the device's positions, the settings and the downloads, and the word that
 * a book's files changed on its server.
 */
class BookSources @Inject constructor(
    val audio: AudioRepository,
    val library: LibraryRepository,
    val store: PlaybackStore,
    val settings: PlayerSettingsStore,
    private val outbox: PlaybackOutbox,
    /** The finished reading of each book from before its listening. */
    val baselines: ReadingBaselines,
    private val conflicts: ListeningConflicts,
    /** What the device keeps of the books: a downloaded one plays without the server. */
    val kept: KeptBooks,
    val syncEvents: PlaybackSyncEvents,
) {
    /** Whether a position of [bookId] still waits to be sent. */
    suspend fun waiting(bookId: BookId): Boolean = outbox.pending().any { it.bookId == bookId }

    /** Whether a state written elsewhere stands unanswered against the positions of [bookId]. */
    fun standing(bookId: BookId): Boolean = bookId in conflicts.standing.value

    suspend fun nextVolume(seriesId: SeriesId, bookId: BookId): NextVolume? =
        (audio.nextAudioVolume(seriesId, bookId) as? ApiResult.Success)?.value
}

/**
 * What playing narrations takes: their loader, the writer of their places, and the cursor the reader follows.
 */
class NarrationOutput @Inject constructor(
    val loader: NarrationLoader,
    val writer: NarrationPositionWriter,
    val cursor: NarrationCursor,
)

/**
 * What the car is given of a profile.
 *
 * @property library what it browses.
 * @property start what a book started from it plays, and from where.
 */
class CarParts @Inject constructor(val library: CarLibrary, val start: CarStart)

/** What the playback service is made of, from the objects of the profile it plays for. */
class PlaybackParts @Inject constructor(
    val sources: BookSources,
    val listening: ListeningOutput,
    val clocks: PlaybackClocks,
    val streams: MediaStreams,
    val shutdown: PlaybackShutdown,
    val lastBooks: LastBookStore,
    val narration: NarrationOutput,
    val network: ProfileNetwork,
    val car: CarParts,
)

/**
 * The app's one player: an ExoPlayer streaming the book's files through the audio cache, shown to the
 * system and to the app's screens as a [MediaSession] of chapters ([ChapterPlayer]). It runs as a media
 * foreground service while playing, so playback goes on with the screen off, in other apps and after the
 * app is swiped away; paused, the notification stays until dismissed.
 *
 * The service only wires the parts: [BookSession] (commands), [PlaybackSessionLoop] (positions, sleep timer,
 * sessions), [NarrationTicker] (the sentence heard, for the reader), [NetworkWatcher], [FocusGuard],
 * [ShakeSensor], [SessionPublisher] and [PlayerNotification]; [BookFollowers] what follows each book.
 *
 * A book is its audiobook or the narration of one of its read-along files: the same player plays either.
 *
 * It plays for the profile open when it is created, and for no other: it is made of the objects of that one
 * profile, taken at once ([OpenEntry]). It is ended before that profile closes ([PlaybackShutdown]), and ends
 * by itself when it finds that the profile is no longer the open one ([leave]), should the closing not have
 * reached it. A profile that closes is kept, and may be the open one again: its book is closed without being
 * forgotten, so that a play button resumes it then; and its session is released at once ([SessionHold]), so
 * that no controller — the system's media controls, another app — holds this service alive, and the next
 * profile gets a service of its own. A controller that still reaches this one meanwhile is refused, and the
 * app's tries again. The system may create it at any time: with no profile open it is made of nothing, gives no
 * session, and stops at the start the system delivers — where a play button's start is paid the foreground
 * it owes, which stopping any sooner would leave unpaid.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    @Inject
    lateinit var profile: CurrentProfile

    /** The profile it was made for; null when none was open at creation: nothing below was made then. */
    private var taken: OpenEntry<PlayerProfileEntry>? = null
    private lateinit var parts: PlaybackParts

    /** Its profile closed: its book is closed, its session released, it gives none any more, and it ends. */
    private var left = false

    /** Whether the system delivered a start: a service that was only bound has nothing to stop. */
    private var startSeen = false

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** For what must be written even as the service ends: the last position, the last session. */
    private val lasting =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> })

    private lateinit var engine: ExoPlayer
    private lateinit var navigator: BookNavigator
    private lateinit var timer: SleepTimer
    private lateinit var book: BookSession
    private lateinit var session: SessionHold
    private lateinit var publisher: SessionPublisher
    private lateinit var loop: PlaybackSessionLoop
    private lateinit var watcher: NetworkWatcher
    private lateinit var focus: FocusGuard
    private lateinit var shake: ShakeSensor
    private lateinit var ticker: NarrationTicker
    private var destroyed = false

    /** Made with the service, not with its profile: a start may owe the foreground while nobody is signed in. */
    private val debt = ForegroundDebt(this) { PlayerNotification.startingNotification(this) }
    private lateinit var followers: BookFollowers

    override fun onCreate() {
        super.onCreate()
        // Nobody is signed in: there is nothing to play, and no object to play it with. Not stopped here: a
        // start that owes the foreground may be on its way, and a service that was only bound ends unbound.
        val open = profile.openEntryOrNull<PlayerProfileEntry>() ?: return
        taken = open
        parts = open.entry.playbackParts()
        PlaybackServiceRunning.set(true)
        parts.shutdown.playerMade()
        engine = newPlaybackEngine(this, parts.streams) { parts.network.unmetered }
        navigator = BookNavigator(engine)
        timer = SleepTimer(parts.clocks.monotonic)
        val texts = PlayerTexts(resources)
        val sleep = SleepDriver(engine, navigator, timer, EngineExactPause(engine, navigator))
        val sources = parts.sources
        val loader =
            BookLoader(
                sources.audio, sources.library, sources.store, sources.settings, sources::waiting, sources::standing,
                sources.kept, texts::part,
            )
        book = BookSession(
            engine, navigator, loader, sleep, timer, sources.settings, sources::nextVolume,
            parts.clocks.monotonic, main,
        )
        // Once its profile closed, a car that still asks is listed nothing of it, and starts nothing.
        val carItems = CarItems(resources, CarCovers.authority(packageName), open.profileId)
        val browser = CarBrowser(parts.car.library, carItems, NoticeAsError(texts.car), main) { left }
        val asks = CarAsks(parts.car.library, texts.car, main, browser::listedBook, { left }) { parts.lastBooks.get() }
        val carStart = CarBookStart(book, main, { parts.car.start.takeUnless { left }?.requestFor(it) }, asks::failed)
        // The cover's address is said as one the car can open; the app's own picture comes from where it did.
        val cover = PlayingCover(CarCovers.authority(packageName), open.profileId)
        val chapters = ChapterPlayer(engine, navigator, book, carStart) {
            cover.said(it.manifest.bookId, it.artworkUrl)
        }
        val commands =
            PlayerCommands(packageName, ::handle, { publisher.buttons() }, browser = browser, asks = asks) {
                // Its profile closed: there is no book of it to resume.
                resumption(parts.lastBooks.takeUnless { left }, main)
            }
        val mediaSession = MediaLibrarySession.Builder(this, chapters, commands)
            .setBitmapLoader(cover.loader(parts.streams.sessionCovers(this)))
            .apply { sessionActivity(null, open.profileId)?.let(::setSessionActivity) }
            .build()
        session = SessionHold(this, mediaSession)
        // Through the hold: once its profile closed the session is released, and nothing is said to it.
        publisher = SessionPublisher(texts, chapters, navigator, { session.held?.setSessionExtras(it) }) {
            session.held?.setMediaButtonPreferences(it)
        }
        // A book played from its download waits for no network.
        watcher = NetworkWatcher(engine, parts.network.online, main) { !book.playsDownload }.also { it.start() }
        focus = FocusGuard(engine, main).also { it.start() }
        shake = ShakeSensor(this, parts.clocks.monotonic, sleep::extend)
        loop = newLoop(sleep).also { it.start() }
        ticker = NarrationTicker(engine, navigator, parts.narration.cursor, main).also { it.start() }
        val activity: (LoadedBook?) -> Unit = {
            sessionActivity(it.readerBookId, open.profileId)?.let { tap -> session.held?.setSessionActivity(tap) }
        }
        followers = BookFollowers(parts, loop, watcher, ticker, activity, lasting).also { it.attach(book) }
        debt.follow(book)
        engine.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) PlayerNotification.dismissContinue(this@PlaybackService)
            }

            // The speed button names the speed.
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                publisher.speed = playbackParameters.speed
            }
        })
        setMediaNotificationProvider(PlayerNotification.provider(this))
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                engine.pause()
                // Its profile closed: there is nothing of it to continue, and the next account is not asked to.
                if (left) return
                // A narration goes on in its reader.
                val extras = book.extras.value
                val tap = sessionActivity(extras.readerBookId, open.profileId)
                offerToContinue(this@PlaybackService, extras.book?.title.orEmpty(), tap)
            }
        })
        watch(open)
    }

    /**
     * A play button started the service in the foreground's name: the debt is paid if no book plays. With no
     * profile to play for — none was open, or it closed — nothing will: the debt is paid at once, and the
     * service stops.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSeen = true
        debt.onStart(intent, noBook = taken == null || !book.hasBook)
        if (forOpenProfile()) return super.onStartCommand(intent, flags, startId)
        debt.settle()
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * None without a profile, nor once its profile closed: the controller is refused. The next profile's own
     * controller may come here before this service has ended; it tries again, and reaches a new one.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (forOpenProfile()) session.held else null

    /**
     * Swiped away: a book that plays, waits for data, is held by a call or waits for the network to resume
     * goes on; otherwise the service ends.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (taken == null) return
        if (stopsWhenTaskRemoved(engine.playWhenReady, engine.playbackState, watcher.willResume)) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        debt.release()
        if (taken == null) return super.onDestroy()
        destroyed = true
        PlaybackServiceRunning.set(false)
        parts.shutdown.unregister()
        followers.release()
        loop.leave()
        loop.stop()
        // No narration any more: the reader stops following one.
        ticker.follow(null)
        ticker.stop()
        watcher.stop()
        focus.stop()
        shake.active = false
        // Released already when its profile closed: not twice.
        session.release()
        engine.release()
        // Only now does the player read no more: the profile's audio cache is released on this word.
        parts.shutdown.playerReleased()
        main.cancel()
        super.onDestroy()
    }

    private fun newLoop(sleep: SleepDriver): PlaybackSessionLoop {
        val sessions = ListeningSessions(parts.clocks.sessions::create) { fileId, report ->
            lasting.launch { parts.listening.sessions.send(fileId, report) }
        }
        val events = object : PlaybackSessionLoop.Events {
            override fun onTick() {
                publisher.refresh()
                val enabled = publisher.settings.shakeToExtend
                shake.active = ShakeDetector.shouldListen(enabled, timer.inLastMinute, engine.isPlaying)
            }

            override fun onPaused() {
                book.onPaused()
                shake.active = false
            }

            override fun onFinished() = book.onFinished()
        }
        return PlaybackSessionLoop(engine, navigator, sleep, sessions, lasting, events)
    }

    /**
     * Whether there is a profile to play for: the one it was made for, still open. Found closed here, before
     * the closing told it, the book is closed first.
     */
    private fun forOpenProfile(): Boolean {
        val open = taken ?: return false
        if (!open.isOpen) leave()
        return !left
    }

    /**
     * Its profile closed: the book stops, its position and its listening are written, and the service gives
     * way. The profile is kept and may be opened again, so the book stays its last book: this closing is not
     * the user's, who would have it forgotten.
     *
     * A service the system has not started yet is not stopped: it was only bound, and ends unbound; or its
     * start is on its way, and stopping before it would leave unpaid the foreground a play button's start owes
     * ([onStartCommand] stops it then).
     *
     * Last, the session is released, which disconnects every controller: one bound to the service — the
     * system's media controls, another app — would keep it alive, with the objects of a profile that closed,
     * in the way of the next profile's. Only after the book was closed: its last position and its listening
     * are in the hands of [lasting] by then, which the session's end does not touch.
     */
    private fun leave() {
        if (left || destroyed) return
        left = true
        // What it asked a tap for names a book of the account that leaves.
        PlayerNotification.dismissContinue(this)
        followers.profileCloses()
        if (startSeen) handle(PlayerCommand.CloseBook) else book.handle(PlayerCommand.CloseBook)
        session.release()
    }

    /** @param open the profile it plays for. */
    private fun watch(open: OpenEntry<PlayerProfileEntry>) {
        // The profile closes: its book stops, kept as its last one, and the service ends, before its objects go.
        // Its closing waits here for the last writes. A profile that closed while this was made refuses.
        val kept = parts.shutdown.register {
            withContext(Dispatchers.Main) { leave() }
            lasting.joinLaunched()
        }
        if (!kept) leave()
        // Not left to the closing alone, which may have given up on a main thread that was busy.
        main.launch {
            open.open.first { !it }
            leave()
        }
        main.launch {
            combine(book.extras, timer.state, watcher.reason) { extras, sleeping, reason ->
                extras.copy(
                    sleep = sleeping,
                    offline = reason == NetworkPause.NoConnection,
                    unreachable = reason == NetworkPause.ServerUnreachable,
                )
            }.collect(publisher::publish)
        }
        main.launch { parts.sources.settings.settings.collect { publisher.settings = it } }
        // A car subscribed to a tab asks for it again when told: nothing is said once the session was let go.
        main.refreshCars(parts.network.waitsForSignIn, parts.car.library.keptAudio) {
            session.held?.notifyChildrenChanged(CarIds.of(it), Int.MAX_VALUE, null)
        }
        main.launch {
            // The book's files changed on the server: the same place in the book, in the new files.
            parts.sources.syncEvents.manifestChanges.collect { id ->
                // An audiobook's files: a narration of the same book plays on as it is.
                val extras = book.extras.value
                if (extras.book?.id != id || extras.narrationFileId != null) return@collect
                // A downloaded one plays the files on the device, whatever the server has now.
                if (followers.playsKept(id)) return@collect
                book.load(id, fromStart = false, play = engine.playWhenReady, at = navigator.bookTimeMs())
            }
        }
    }

    /**
     * A command of a controller, or of the service itself. Closing the book ("Stop listening", the end
     * panel's "Close", the notification dismissed, the profile closing) saves its position and its
     * listening first, and ends the service once no screen holds it. The user's closing forgets the book; the
     * profile's does not ([leave] says so to the [BookFollowers] before it closes).
     */
    private fun handle(command: PlayerCommand) {
        if (command != PlayerCommand.CloseBook) {
            // Its profile closed: nothing is loaded any more with the objects of an account that left.
            if (!left) book.handle(command)
            return
        }
        // A play button's start still waiting for its book owes the foreground before the service ends.
        debt.settle()
        book.handle(PlayerCommand.CloseBook)
        pauseAllPlayersAndStopSelf()
    }

    companion object {
        fun stopsWhenTaskRemoved(playWhenReady: Boolean, playbackState: Int, resumesByItself: Boolean): Boolean =
            !resumesByItself &&
                (!playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)
    }
}

/**
 * Waits for what was launched in this scope so far: the last writes of a book that was just closed, which
 * whoever closes the profile lets end before the profile's stores go.
 */
internal suspend fun CoroutineScope.joinLaunched() = coroutineContext.job.children.toList().joinAll()
