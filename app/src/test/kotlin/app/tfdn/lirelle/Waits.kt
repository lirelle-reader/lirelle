package app.tfdn.lirelle

import android.os.Looper
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.robolectric.Shadows.shadowOf

/**
 * How long a test waits for what the application does on its own threads — a profile opened, closed, its
 * folders deleted — before it fails. One limit for every such wait, and a generous one: a wait that succeeds
 * ends as soon as its condition holds, so only a test that fails pays for it, and a runner that shares its
 * machine may be many times slower than the one the tests were written on.
 */
internal val WaitLimit: Duration = 60.seconds

/** How often a condition waited for is looked at: often, so that the wait costs nothing when it holds soon. */
private val WaitStep = 5.milliseconds

/**
 * Waits for [condition], letting the main thread, which is the test's, do what is asked of it meanwhile: part
 * of closing a profile is done there. A wait that expires fails the test, saying [what] it waited for: it
 * never gives up silently, which would leave a later assertion to fail for a reason nobody can read.
 */
internal suspend fun until(what: String, condition: suspend () -> Boolean) {
    awaited(what) { Unit.takeIf { condition() } }
}

/** [until], for who needs what it waited for: waits for [find] to give something, and returns it. */
internal suspend fun <T : Any> awaited(what: String, find: suspend () -> T?): T {
    var found = find()
    if (found != null) return found
    withTimeoutOrNull(WaitLimit) {
        while (found == null) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(WaitStep)
            found = find()
        }
    }
    return found ?: throw AssertionError("Waited $WaitLimit in vain for this: $what")
}

/**
 * Skips a test that makes a folder unwritable where that stops nobody: an administrator, which is what a
 * runner's container runs the tests as, writes in it all the same.
 */
internal fun assumeFoldersCanBeLocked() {
    val folder = Files.createTempDirectory("locked").toFile()
    folder.setWritable(false)
    val locked = runCatching { !File(folder, "probe").createNewFile() }.getOrDefault(true)
    folder.setWritable(true)
    folder.deleteRecursively()
    assumeTrue("folders cannot be made unwritable for this user", locked)
}
