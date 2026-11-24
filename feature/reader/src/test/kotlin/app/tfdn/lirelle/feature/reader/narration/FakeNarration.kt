package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.reader.relocated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** The book and the file of the reader's harness. */
val NarratedBook = BookId("12")
val TextFile = FileId("1")

/** Notes what the narration's bar and panel ask, as `name` or `name(argument)`, oldest first. */
class RecordingNarrationActions : NarrationActions {
    val calls = mutableListOf<String>()

    override fun playPause() = note("playPause")
    override fun previousSentence() = note("previousSentence")
    override fun nextSentence() = note("nextSentence")
    override fun back() = note("back")
    override fun forward() = note("forward")
    override fun seekInSection(fraction: Float) = note("seekInSection($fraction)")
    override fun goToSection(index: Int) = note("goToSection($index)")
    override fun goBack() = note("goBack")
    override fun setSpeed(speed: Float) = note("setSpeed($speed)")
    override fun stop() = note("stop")
    override fun startSleep(choice: SleepChoice) = note("startSleep($choice)")
    override fun cancelSleep() = note("cancelSleep")
    override fun setShakeToExtend(enabled: Boolean) = note("setShakeToExtend($enabled)")

    private fun note(call: String) {
        calls += call
    }
}

/** A player without a service: the test sets what it shows, and reads what was asked of it. */
class FakeNarrationPlayer : ReaderNarrationPlayer {
    override val actions = RecordingNarrationActions()
    override val narration = MutableStateFlow<NarrationUi?>(null)
    override val heard = MutableStateFlow<HeardSentence?>(null)
    override val skipped = MutableSharedFlow<SkippedNotice>(extraBufferCapacity = 8)
    override val failure = MutableStateFlow<NarrationFailure?>(null)

    val narrated = mutableListOf<NarrationStart.AtSentence>()

    /** Each narration loaded again where it was left: whether it was to play. */
    val resumed = mutableListOf<Boolean>()
    var pauses = 0

    override fun narrate(bookId: BookId, fileId: FileId, start: NarrationStart, play: Boolean) {
        check(bookId == NarratedBook && fileId == TextFile) { "Another book: $bookId, $fileId" }
        when (start) {
            is NarrationStart.AtSentence -> narrated += start
            else -> resumed += play
        }
    }

    override fun pause() {
        pauses++
    }

    var plays = 0
    var retries = 0

    override fun play() {
        plays++
    }

    override fun retryNarration() {
        retries++
    }

    /** The service publishes the sentence [fragment] of [section], playing or paused. */
    fun hear(
        section: Int,
        fragment: String,
        playing: Boolean = true,
        bookId: BookId = NarratedBook,
        fileId: FileId = TextFile,
    ) {
        heard.value = HeardSentence(bookId, fileId, section, fragment, SpineHrefs[section], playing)
    }
}

/** Maps by request, each answered once [gate] opens, when set; [map] is null when it cannot be had. */
class FakeReadAlongMapStore(var map: ReadAlongMap? = sampleMap()) : ReadAlongMapStore {
    val asked = mutableListOf<Triple<BookId, FileId, String?>>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun map(bookId: BookId, fileId: FileId, contentVersion: String?): ReadAlongMap? {
        asked += Triple(bookId, fileId, contentVersion)
        gate?.await()
        return map
    }
}

val SpineHrefs = listOf("one.xhtml", "two.xhtml", "three.xhtml", "four.xhtml")

/**
 * Sections 0 and 2 narrated (`a1`, `a2`, `a3`; `c1`, `c2`), 1 and 3 not: four seconds per sentence, one track.
 */
fun sampleMap(): ReadAlongMap {
    val sentences = listOf(0 to "a1", 0 to "a2", 0 to "a3", 2 to "c1", 2 to "c2")
    val items = sentences.mapIndexed { index, (section, id) ->
        OverlayItem(section, SpineHrefs[section], id, "audio.mp3", index * 4_000L, (index + 1) * 4_000L)
    }
    val playlist = ReadAlongPlaylist(
        bookId = NarratedBook,
        fileId = TextFile,
        durationMs = sentences.size * 4_000L,
        items = items,
        sections = listOf(OverlaySection(0, SpineHrefs[0], "One"), OverlaySection(2, SpineHrefs[2], "Three")),
        resources = listOf(OverlayResource("audio.mp3", "audio/mpeg", 1_000)),
    )
    val spine = SpineHrefs.mapIndexed { index, href -> SpineEntry(index, href, 10_000) }
    return ReadAlongMap(NarratedBook, TextFile, null, playlist, spine)
}

/** This book's narration as the player shows it. */
fun narrationUi(
    playing: Boolean = true,
    finished: Boolean = false,
    bookId: BookId = NarratedBook,
    fileId: FileId = TextFile,
) = NarrationUi(
    bookId = bookId,
    fileId = fileId,
    title = "A Complete Book",
    sectionIndex = 0,
    sectionTitle = "One",
    sectionNumber = 1,
    sectionCount = 2,
    elapsedMs = 0,
    sectionDurationMs = 12_000,
    bookPercent = 0.0,
    timeLeftMs = 20_000,
    playing = playing,
    speed = 1f,
    sleep = null,
    sections = emptyList(),
    sentence = null,
    finished = finished,
)

/** The page moved to [cfi] in [sectionIndex]: by the user, or with [byNarration] to follow the narration. */
fun pageAt(cfi: String, fraction: Double = 0.1, sectionIndex: Int = 0, byNarration: Boolean = false) =
    relocated(cfi = cfi, fraction = fraction).copy(sectionIndex = sectionIndex, byNarration = byNarration)

/** The device's narration places, in memory. */
class MemoryNarrationStore : NarrationStore {
    private val narrations = mutableMapOf<FileId, LocalNarration>()

    override suspend fun get(fileId: FileId) = narrations[fileId]

    override suspend fun put(n: LocalNarration) {
        narrations[n.fileId] = n
    }
}
