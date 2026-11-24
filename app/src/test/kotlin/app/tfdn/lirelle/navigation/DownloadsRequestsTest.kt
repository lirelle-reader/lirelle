package app.tfdn.lirelle.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The downloads asked for by a notification are those of the profile that posted it. */
class DownloadsRequestsTest {

    private var open: String? = "p1"
    private val requests = DownloadsRequests { open }

    @Test
    fun `a request of the open profile is taken once`() {
        requests.open("p1")

        assertThat(requests.take()).isTrue()
        assertThat(requests.take()).isFalse()
    }

    @Test
    fun `a request of another profile selects nothing, and is over`() {
        requests.open("p2")

        assertThat(requests.take()).isFalse()
        assertThat(requests.pending).isFalse()
    }

    @Test
    fun `a request of a profile left before it is taken selects nothing for the next`() {
        requests.open("p1")
        open = "p2"

        assertThat(requests.take()).isFalse()
    }

    /** A notification posted before the update, which the system still shows after it. */
    @Test
    fun `a request that names no profile is followed for the open one`() {
        requests.open()

        assertThat(requests.take()).isTrue()
    }

    @Test
    fun `the profile of an earlier request does not carry over to the next`() {
        requests.open("p2")
        requests.open()
        assertThat(requests.take()).isTrue()

        requests.open("p1")
        requests.open("p2")
        assertThat(requests.take()).isFalse()
    }
}
