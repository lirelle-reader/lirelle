package app.tfdn.lirelle.navigation

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A reader asked for by a notification is a book of the profile that posted it. */
class ReaderRequestsTest {

    private var open: String? = "p1"
    private val requests = ReaderRequests { open }

    @Test
    fun `a request of the open profile is taken once`() {
        requests.open(BookId("42"), "p1")

        assertThat(requests.take()).isEqualTo(BookId("42"))
        assertThat(requests.take()).isNull()
    }

    @Test
    fun `a request of another profile opens nothing, and is over`() {
        requests.open(BookId("42"), "p1")
        open = "p2"

        assertThat(requests.take()).isNull()
        assertThat(requests.pending).isNull()
    }

    @Test
    fun `a request of a profile that closed opens nothing while nobody is signed in`() {
        requests.open(BookId("42"), "p1")
        open = null

        assertThat(requests.take()).isNull()
    }

    @Test
    fun `a request that names no profile opens nothing for the open one`() {
        requests.open(BookId("42"))

        assertThat(requests.take()).isNull()
    }

    @Test
    fun `the profile of an earlier request does not carry over to the next`() {
        requests.open(BookId("42"), "p1")
        requests.open(BookId("43"), "p2")

        assertThat(requests.take()).isNull()
    }
}
