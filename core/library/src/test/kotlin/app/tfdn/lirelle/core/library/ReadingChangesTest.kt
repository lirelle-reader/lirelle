package app.tfdn.lirelle.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingChangesTest {

    @Test
    fun `each change is a version above the one before`() {
        val changes = ReadingChanges()
        val start = changes.version.value

        changes.changed()
        val first = changes.version.value
        changes.changed()

        assertThat(first).isGreaterThan(start)
        assertThat(changes.version.value).isGreaterThan(first)
    }

    @Test
    fun `a change made before anyone listens is still there to see`() = runTest {
        val changes = ReadingChanges()
        val start = changes.version.value

        changes.changed()

        assertThat(changes.version.first()).isGreaterThan(start)
    }

    @Test
    fun `a listener sees the last of several changes it had no time to see one by one`() = runTest {
        val changes = ReadingChanges()
        val start = changes.version.value
        val seen = mutableListOf<Long>()
        backgroundScope.launch { changes.version.collect { seen += it } }
        runCurrent()

        changes.changed()
        changes.changed()
        runCurrent()

        assertThat(seen.first()).isEqualTo(start)
        assertThat(seen.last()).isEqualTo(changes.version.value)
        assertThat(seen.last()).isGreaterThan(start)
    }
}
