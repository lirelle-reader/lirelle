package app.tfdn.lirelle.core.comics.fetch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FetchQueueTest {

    @Test
    fun `the page shown is next, whatever waits`() {
        val queue = FetchQueue(waiting = listOf(4, 5), inFlight = emptySet())

        assertThat(queue.next(shown = 9)).isEqualTo(9)
    }

    @Test
    fun `the page shown is next even when both slots are busy`() {
        val queue = FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2, 3))

        assertThat(queue.next(shown = 9)).isEqualTo(9)
    }

    @Test
    fun `a page shown that is being fetched is not asked for twice`() {
        val queue = FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2, 3))

        assertThat(queue.next(shown = 3)).isNull()
    }

    @Test
    fun `the window goes on in its order while a slot is free`() {
        assertThat(FetchQueue(waiting = listOf(4, 5), inFlight = emptySet()).next()).isEqualTo(4)
        assertThat(FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2)).next()).isEqualTo(4)
    }

    @Test
    fun `the window waits when two fetches are going`() {
        assertThat(FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2, 3)).next()).isNull()
    }

    @Test
    fun `a page of the window that is being fetched is passed over`() {
        assertThat(FetchQueue(waiting = listOf(4, 5), inFlight = setOf(4)).next()).isEqualTo(5)
    }

    @Test
    fun `nothing waits, nothing is next`() {
        assertThat(FetchQueue().next()).isNull()
    }

    @Test
    fun `a fetch that starts leaves the line and takes a slot`() {
        val queue = FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2)).started(4)

        assertThat(queue).isEqualTo(FetchQueue(waiting = listOf(5), inFlight = setOf(2, 4)))
    }

    @Test
    fun `a fetch that ends gives its slot back`() {
        val queue = FetchQueue(waiting = listOf(5), inFlight = setOf(2, 4)).finished(2)

        assertThat(queue).isEqualTo(FetchQueue(waiting = listOf(5), inFlight = setOf(4)))
    }

    @Test
    fun `a jump drops what waits and the fetches outside the new window`() {
        val queue = FetchQueue(waiting = listOf(4, 5), inFlight = setOf(2, 3, 12))

        val jump = queue.jumped(window = setOf(10, 11, 12, 13))

        assertThat(jump.dropped).containsExactly(2, 3)
        assertThat(jump.queue).isEqualTo(FetchQueue(waiting = emptyList(), inFlight = setOf(12)))
    }
}
