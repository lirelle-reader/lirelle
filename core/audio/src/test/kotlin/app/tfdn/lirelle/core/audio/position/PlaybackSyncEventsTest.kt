package app.tfdn.lirelle.core.audio.position

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackSyncEventsTest {

    private val events = PlaybackSyncEvents()

    @Test
    fun `a listening player hears of changed manifests`() = runTest {
        events.manifestChanges.test {
            events.manifestChanged(OtherBook)

            assertThat(awaitItem()).isEqualTo(OtherBook)
        }
    }

    @Test
    fun `nothing is kept for a player that was not listening`() = runTest {
        events.manifestChanged(SampleBook)

        events.manifestChanges.test { expectNoEvents() }
    }
}
