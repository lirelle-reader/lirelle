package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.keeps
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.feature.player.service.NarrationStart
import app.tfdn.lirelle.feature.reader.ReaderCore
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.NarrationTarget
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a narration starts from the reader: the page finds the sentence among the narrated ids of its section,
 * which the narration's map gives, and the player narrates from it. A start before the sentence heard is a move
 * back the user meant: the text position goes there too, at once (spec 8.2).
 *
 * @param narrate the reader was opened to narrate: with nothing loaded, the narration starts from the page shown.
 */
internal class NarrationStarts(
    private val core: ReaderCore,
    private val services: NarrationServices,
    private val following: NarrationFollowing,
    private var narrate: Boolean,
) {
    private var map: ReadAlongMap? = null

    /** The file and the version [map] was had for: a map a download kept may be of another version. */
    private var mapOf: Pair<FileId, String?>? = null
    private val fetching = Mutex()
    private var nextRequest = 0

    /** Each start asked, and each move of the user's, makes what was asked before stale. */
    private var asked = 0

    /** The one find waited for: its request and its section. Any other answer is stale. */
    private var find: Pair<Int, Int>? = null

    /** The one sentence position waited for: its request, and how far into the book it is (see [askPosition]). */
    private var position: Pair<Int, Double?>? = null

    /** What was last asked, to ask again after the map could not be had. */
    private var lastAsked: (() -> Unit)? = null

    /**
     * The page shows another place; [moved] false for the place the book opened at, where a narration asked for
     * starts, or plays on where it was paused. One that ended starts again from the page, not from its beginning.
     */
    fun onRelocated(moved: Boolean) {
        if (moved || !narrate) return
        narrate = false
        val heard = services.player.heard.value?.takeIf { it.bookId == core.bookId && it.fileId == core.fileId }
        when {
            heard == null || core.current.narration.ended -> find(NarrationTarget.Page, core.sectionIndex)
            !heard.playing -> services.player.play()
        }
    }

    /** The user moved, or the page opened the book again: no answer to what was asked before is taken. */
    fun forget() {
        asked++
        find = null
        position = null
    }

    /**
     * Asks the page for the sentence to start from in [sectionIndex]; [then] once it is asked. A section without
     * narration starts at the next narrated one, without asking. Only the latest start asked goes on.
     */
    fun find(target: NarrationTarget, sectionIndex: Int?, then: () -> Unit = {}) {
        if (sectionIndex == null || core.book?.file?.hasReadAlong != true || !core.driver.opened) return
        lastAsked = { find(target, sectionIndex, then) }
        forget()
        val ask = asked
        core.scope.launch {
            val map = map() ?: return@launch
            if (ask != asked) return@launch
            val ids = map.fragmentsOf(sectionIndex)
            if (ids.isEmpty()) {
                then()
                return@launch startAt(map, map.firstSentenceFrom(sectionIndex))
            }
            val request = nextRequest++
            val prefer = if (target == NarrationTarget.Page) lastHeardIn(sectionIndex) else null
            if (ask != asked) return@launch
            if (core.driver.send(HostMessage.NarrationFind(request, sectionIndex, ids, target, prefer))) {
                find = request to sectionIndex
            }
            then()
        }
    }

    /**
     * The sentence of [sectionIndex] the narration of this file was last heard at, on this device: a start from
     * the page goes on from there when the page shows it, rather than from the page's first sentence (a whole
     * spread back on a tablet). Null when it was last heard in another section, or never.
     */
    private suspend fun lastHeardIn(sectionIndex: Int): String? {
        following.sentence?.let { return it.fragment.takeIf { _ -> it.sectionIndex == sectionIndex } }
        val place = core.fileId?.let { services.places.get(it) }?.place ?: return null
        return place.fragment.substringAfter('#').takeIf { place.sectionIndex == sectionIndex && it.isNotEmpty() }
    }

    /** The page's answer: its sentence, else the first one of the next narrated section. */
    fun onFound(message: PageMessage.NarrationFound) {
        val (_, sectionIndex) = find?.takeIf { it.first == message.requestId } ?: return
        find = null
        val map = map ?: return
        val found = message.id?.let { map.sentenceOf(sectionIndex, it) }
        startAt(map, found ?: map.firstSentenceFrom(sectionIndex + 1))
    }

    /**
     * The position of a sentence started from, or landed on, sent at once as the text position: as far into the
     * book as the page says it is, else where the page was when it was asked.
     */
    fun onPosition(message: PageMessage.NarrationCfiOf) {
        val (_, at) = position?.takeIf { it.first == message.requestId } ?: return
        position = null
        val cfi = message.cfi ?: return
        val fraction = message.fraction ?: at ?: return
        val session = core.session ?: return
        core.scope.launch {
            session.relocated(cfi, fraction, moved = true, byUser = false)
            session.flush()
        }
    }

    /** "Try again": what was last asked, the map asked again. */
    fun retry() {
        core.update { it.copy(narration = it.narration.copy(unavailable = false)) }
        lastAsked?.invoke()
    }

    private fun startAt(map: ReadAlongMap, sentence: ReadAlongMap.Sentence?) {
        val fileId = core.fileId
        if (sentence == null || fileId == null) return
        val heard = following.sentence?.let { map.sentenceOf(it.sectionIndex, it.fragment) }
        if (heard != null && sentence.ordinal < heard.ordinal) askPosition(sentence)
        following.starting(sentence.sectionIndex, sentence.fragment)
        val start = NarrationStart.AtSentence(sentence.sectionIndex, sentence.fragment)
        services.player.narrate(core.bookId, fileId, start)
    }

    /**
     * Asks the page where [sentence] starts, and how far into the book, to send it as the text position. A page
     * that cannot tell how far gets the place of the page shown now.
     */
    fun askPosition(sentence: ReadAlongMap.Sentence) {
        if (sentence.fragment.isEmpty()) return
        val request = nextRequest++
        if (core.driver.send(HostMessage.NarrationCfi(request, sentence.sectionIndex, sentence.fragment))) {
            position = request to core.current.location?.fraction
        }
    }

    /**
     * The narration's map of the file read, once had, and had again for another file or version; when it cannot
     * be, even from what a download kept, the narration is unavailable, unless [quiet] (nothing the user asked
     * waits for it). One fetch at a time: a second start waits for the first one's map.
     *
     * A narration the device has whole starts from the map its download kept, which is the one the player plays.
     */
    suspend fun map(quiet: Boolean = false): ReadAlongMap? = fetching.withLock {
        val file = core.book?.file ?: return@withLock null
        map?.takeIf { mapOf == file.id to file.contentVersion }?.let { return@withLock it }
        // Said again at each try: a failure after a failure shows again.
        if (!quiet) core.update { it.copy(narration = it.narration.copy(unavailable = false)) }
        val downloaded = services.downloads.keeps(core.bookId, DownloadFormat.Narration)
        // The one a download kept: first when the narration is downloaded, else when the device's own maps and
        // the server have none to give.
        val fetched = (if (downloaded) services.kept.map(core.bookId, file.id) else null)
            ?: services.maps.map(core.bookId, file.id, file.contentVersion)
            ?: if (downloaded) null else services.kept.map(core.bookId, file.id)
        map = fetched
        mapOf = fetched?.let { file.id to file.contentVersion }
        if (!quiet) core.update { it.copy(narration = it.narration.copy(unavailable = fetched == null)) }
        fetched
    }
}
