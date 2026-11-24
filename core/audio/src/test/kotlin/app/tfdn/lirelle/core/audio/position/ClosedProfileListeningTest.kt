package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The playback service writes a book's last position and its last listening session in a scope of its own; a
 * profile that closes waits for them a few seconds only. Written later, they meet stores whose profile is
 * closed, and whose folder may be deleted already.
 */
class ClosedProfileListeningTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `what is written once the profile closed is dropped quietly, and its folder is not made again`() =
        runBlocking<Unit> {
            val folders = ProfileFolders(File(folder.root, "files/p1"), File(folder.root, "cache/p1"))
            val life = CoroutineScope(SupervisorJob())
            val dataStore = folders.newDataStore("listening", life)
            val store = DataStorePlaybackStore(dataStore)
            val outbox = DataStorePlaybackOutbox(dataStore)
            val sessions = DataStoreListeningSessionQueue(dataStore)
            store.put(samplePlayback())
            assertThat(folders.dataStore("listening").isFile).isTrue()

            life.coroutineContext.job.cancelAndJoin()
            assertThat(folders.files.deleteRecursively()).isTrue()

            val failures = mutableListOf<Throwable>()
            val lasting =
                CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, failure -> failures += failure })
            withTimeout(10.seconds) {
                lasting.launch { store.put(samplePlayback(positionMs = 120_000)) }.join()
                lasting.launch { outbox.enqueue(samplePlayback(positionMs = 120_000)) }.join()
                lasting.launch { sessions.enqueue(FileId("50"), sampleSession()) }.join()
                lasting.launch { store.get(SampleBook) }.join()
            }

            assertThat(failures).isEmpty()
            assertThat(folders.files.exists()).isFalse()
        }
}
