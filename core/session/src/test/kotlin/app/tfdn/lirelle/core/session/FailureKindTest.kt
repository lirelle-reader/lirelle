package app.tfdn.lirelle.core.session

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test

class FailureKindTest {

    private fun failure(vararg frames: StackTraceElement): Throwable =
        IllegalStateException("/data/user/0/secret", IOException("https://books.example.com refused alice")).apply {
            stackTrace = arrayOf(*frames)
        }

    @Test
    fun `the kinds of the failure and of its cause are told, never what they say`() {
        val told = failure(StackTraceElement("a.b", "c", "SourceFile", 12)).kindAndFrames()

        assertThat(told).contains("java.lang.IllegalStateException <- java.io.IOException")
        assertThat(told).doesNotContain("secret")
        assertThat(told).doesNotContain("example.com")
        assertThat(told).doesNotContain("alice")
    }

    @Test
    fun `the places are told as they are, in a build that renamed the application's classes too`() {
        val told = failure(
            StackTraceElement("a.b", "c", "SourceFile", 12),
            StackTraceElement("kotlinx.coroutines.internal.d", "e", "SourceFile", 3),
        ).kindAndFrames()

        assertThat(told).endsWith(" at a.b.c:12 < kotlinx.coroutines.internal.d.e:3")
    }

    @Test
    fun `only the first places are told`() {
        val frames = Array(20) { StackTraceElement("a.b", "m$it", "SourceFile", it) }

        val told = failure(*frames).kindAndFrames()

        assertThat(told).contains("a.b.m5:5")
        assertThat(told).doesNotContain("a.b.m6:6")
    }

    @Test
    fun `a failure without a trace is told by its kinds alone`() {
        assertThat(failure().kindAndFrames()).isEqualTo("java.lang.IllegalStateException <- java.io.IOException")
    }
}
