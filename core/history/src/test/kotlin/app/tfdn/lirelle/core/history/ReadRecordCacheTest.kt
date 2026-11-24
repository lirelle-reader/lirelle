package app.tfdn.lirelle.core.history

import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ReadRecordCacheTest {

    /** The read named [n]'s text, as a source names it. */
    private fun id(n: Int) = ReadId("$n")

    private fun record(n: Int) = ReadRecord(
        id(n), BookId("1"), "Title $n", emptyList(), null, null, null, null, null, ReadOutcome.Completed,
        1, null, null, Duration.ZERO, null, 0, 0, false,
    )

    @Test
    fun `a stored record is found by id`() {
        val cache = ReadRecordCache()
        cache.put(listOf(record(1), record(2)))

        assertThat(cache.get(id(2))?.title).isEqualTo("Title 2")
    }

    @Test
    fun `an unknown id is null`() {
        assertThat(ReadRecordCache().get(id(9))).isNull()
    }

    @Test
    fun `updating a rating changes the stored record and announces it`() = runTest {
        val cache = ReadRecordCache()
        cache.put(listOf(record(1)))
        cache.ratingUpdates.test {
            cache.updateRating(id(1), 5)

            assertThat(awaitItem()).isEqualTo(RatingUpdate(id(1), 5))
            assertThat(cache.get(id(1))?.rating).isEqualTo(5)
        }
    }

    @Test
    fun `updating the rating of an absent id does nothing`() = runTest {
        val cache = ReadRecordCache()
        cache.ratingUpdates.test {
            cache.updateRating(id(9), 3)

            expectNoEvents()
            assertThat(cache.get(id(9))).isNull()
        }
    }

    @Test
    fun `the cache is bounded and drops the oldest`() {
        val cache = ReadRecordCache()
        cache.put((1..250).map(::record))

        assertThat(cache.get(id(1))).isNull()
        assertThat(cache.get(id(50))).isNull()
        assertThat(cache.get(id(51))).isNotNull()
        assertThat(cache.get(id(250))).isNotNull()
    }
}
