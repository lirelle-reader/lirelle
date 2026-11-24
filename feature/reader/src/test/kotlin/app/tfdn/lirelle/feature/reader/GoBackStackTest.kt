package app.tfdn.lirelle.feature.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoBackStackTest {
    private val stack = GoBackStack()

    @Test
    fun `it starts empty and gives nothing back`() {
        assertThat(stack.isEmpty).isTrue()
        assertThat(stack.pop()).isNull()
    }

    @Test
    fun `it returns to the places jumps were made from, the last one first`() {
        stack.push("a")
        stack.push("b")

        assertThat(stack.pop()).isEqualTo("b")
        assertThat(stack.isEmpty).isFalse()
        assertThat(stack.pop()).isEqualTo("a")
        assertThat(stack.isEmpty).isTrue()
    }

    @Test
    fun `one page turned keeps the place, two forget every place`() {
        stack.push("a")
        stack.push("b")

        stack.onPageTurn()
        assertThat(stack.isEmpty).isFalse()
        stack.onPageTurn()

        assertThat(stack.isEmpty).isTrue()
        assertThat(stack.pop()).isNull()
    }

    @Test
    fun `a new jump starts the count of pages again`() {
        stack.push("a")
        stack.onPageTurn()
        stack.push("b")
        stack.onPageTurn()

        assertThat(stack.pop()).isEqualTo("b")
    }

    @Test
    fun `going back starts the count of pages again`() {
        stack.push("a")
        stack.push("b")
        stack.onPageTurn()
        stack.pop()
        stack.onPageTurn()

        assertThat(stack.pop()).isEqualTo("a")
    }

    @Test
    fun `pages turned without a jump count for nothing`() {
        stack.onPageTurn()
        stack.onPageTurn()
        stack.push("a")
        stack.onPageTurn()

        assertThat(stack.isEmpty).isFalse()
    }

    @Test
    fun `only the latest places are kept`() {
        repeat(100) { stack.push("place $it") }

        var count = 0
        while (stack.pop() != null) count++

        assertThat(count).isEqualTo(32)
    }
}
