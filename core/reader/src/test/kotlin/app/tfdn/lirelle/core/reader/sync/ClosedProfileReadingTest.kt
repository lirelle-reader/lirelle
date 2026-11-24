package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.marks.DataStoreMarks
import app.tfdn.lirelle.core.reader.marks.MarkSamples
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A reader that is leaving writes its last position, its reading session and its last marks in a scope that
 * outlives the screen; the player does the same for a narration. Written after the profile closed, they meet
 * stores whose profile is gone, and whose folder may be deleted already.
 */
class ClosedProfileReadingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val at = Instant.parse("2026-11-14T10:00:00Z")
    private val file = FileId("12")
    private val position = LocalPosition(file, "epubcfi(/6/4!/4/2/1:0)", 12.5, at)
    private val narration = LocalNarration(file, NarrationPlace(95.25, "ch1.xhtml#s3", 2), 12.0, at)
    private val session = ReadingSessionReport("s-1", at, at.plusSeconds(600), 540, 3.5, 16.0)

    private val folders by lazy { ProfileFolders(File(folder.root, "files/p1"), File(folder.root, "cache/p1")) }
    private val life = CoroutineScope(SupervisorJob())
    private val dataStore by lazy { folders.newDataStore("reading", life) }

    private val failures = mutableListOf<Throwable>()
    private val lasting =
        CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, failure -> failures += failure })

    /** The profile closes, then is removed: its folder is deleted. */
    private suspend fun closeAndRemove() {
        assertThat(folders.dataStore("reading").isFile).isTrue()
        life.coroutineContext.job.cancelAndJoin()
        assertThat(folders.files.deleteRecursively()).isTrue()
    }

    /** Each of [writes] in the scope that outlives the screen, one after the other. */
    private suspend fun late(vararg writes: suspend () -> Unit) = withTimeout(10.seconds) {
        writes.forEach { write -> lasting.launch { write() }.join() }
    }

    @Test
    fun `a position written once the profile closed is dropped quietly, and its folder is not made again`() =
        runBlocking<Unit> {
            val positions = DataStorePositionStore(dataStore)
            val narrations = DataStoreNarrationStore(dataStore)
            val outbox = DataStoreOutbox(dataStore)
            positions.put(position)
            closeAndRemove()

            late(
                { positions.put(position) },
                { narrations.put(narration) },
                { outbox.enqueuePosition(position) },
                { outbox.enqueueNarration(narration) },
                { outbox.enqueueSession(file, session) },
                { positions.get(file) },
                { outbox.pending() },
            )

            assertThat(failures).isEmpty()
            assertThat(folders.files.exists()).isFalse()
        }

    @Test
    fun `a mark written once the profile closed is dropped quietly, and its folder is not made again`() =
        runBlocking<Unit> {
            val marks = DataStoreMarks(dataStore, MarkSamples.Wall)
            marks.record(MarkSamples.addHighlight())
            closeAndRemove()

            late(
                { marks.record(MarkSamples.addBookmark()) },
                { marks.markSent(MarkSamples.addHighlight()) },
                { marks.pending() },
                { marks.book(MarkSamples.Book).first() },
            )

            assertThat(failures).isEmpty()
            assertThat(folders.files.exists()).isFalse()
        }
}
