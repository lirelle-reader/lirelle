package app.tfdn.lirelle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutboxKickerTest {

    private var kicks = 0
    private val kicker = OutboxKicker({ kicks++ })

    @Test
    fun `the outbox is kicked when the profile opens`() {
        kicker.start()

        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `the reading, marks and listening flushers are all kicked`() {
        var marksKicks = 0
        var listeningKicks = 0
        val all = OutboxKicker({ kicks++ }, { marksKicks++ }, { listeningKicks++ })

        all.start()
        all.onNetworkAvailable()

        assertThat(kicks).isEqualTo(2)
        assertThat(marksKicks).isEqualTo(2)
        assertThat(listeningKicks).isEqualTo(2)
    }

    @Test
    fun `it is kicked when the network comes back`() {
        kicker.start()

        kicker.onNetworkAvailable()
        kicker.onNetworkAvailable()

        assertThat(kicks).isEqualTo(3)
    }
}
