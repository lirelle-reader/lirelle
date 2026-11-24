package app.tfdn.lirelle.core.designsystem

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowWidthClassTest {

    @Test
    fun `phone width is compact`() {
        assertThat(WindowWidthClass.fromWidthDp(411f)).isEqualTo(WindowWidthClass.Compact)
    }

    @Test
    fun `600dp is the first medium width`() {
        assertThat(WindowWidthClass.fromWidthDp(599.9f)).isEqualTo(WindowWidthClass.Compact)
        assertThat(WindowWidthClass.fromWidthDp(600f)).isEqualTo(WindowWidthClass.Medium)
    }

    @Test
    fun `840dp is the first expanded width`() {
        assertThat(WindowWidthClass.fromWidthDp(839.9f)).isEqualTo(WindowWidthClass.Medium)
        assertThat(WindowWidthClass.fromWidthDp(840f)).isEqualTo(WindowWidthClass.Expanded)
    }

    @Test
    fun `tablet portrait width is expanded`() {
        assertThat(WindowWidthClass.fromWidthDp(1001f)).isEqualTo(WindowWidthClass.Expanded)
    }
}
