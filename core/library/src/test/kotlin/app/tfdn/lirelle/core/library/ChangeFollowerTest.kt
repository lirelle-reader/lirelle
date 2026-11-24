package app.tfdn.lirelle.core.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeFollowerTest {

    private val changes = ReadingChanges()
    private var reloads = 0

    private fun TestScope.follower() =
        ChangeFollower(changes, backgroundScope, idle = {}, reload = { reloads++ }).also {
            it.start(changes.version.value)
            runCurrent()
        }

    @Test
    fun `a change while the screen is in front reloads it at once`() = runTest {
        val follower = follower()
        follower.shown()

        changes.changed()
        runCurrent()

        assertThat(reloads).isEqualTo(1)
    }

    @Test
    fun `changes while the screen is not in front reload nothing until it is shown, then once`() = runTest {
        val follower = follower()
        follower.shown()
        follower.hidden()

        repeat(times = 5) {
            changes.changed()
            runCurrent()
        }
        assertThat(reloads).isEqualTo(0)

        assertThat(follower.shown()).isTrue()
        runCurrent()
        assertThat(reloads).isEqualTo(1)

        follower.hidden()
        assertThat(follower.shown()).isFalse()
        runCurrent()
        assertThat(reloads).isEqualTo(1)
    }

    @Test
    fun `a screen never shown yet waits too`() = runTest {
        val follower = follower()

        changes.changed()
        runCurrent()
        assertThat(reloads).isEqualTo(0)

        follower.shown()
        runCurrent()
        assertThat(reloads).isEqualTo(1)
    }
}
