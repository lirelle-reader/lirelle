package app.tfdn.lirelle.feature.history

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.history.model.Highlight
import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.HistoryPeriod
import app.tfdn.lirelle.core.history.model.RatingResult
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadOutcome
import app.tfdn.lirelle.core.history.model.ReadPage
import app.tfdn.lirelle.core.history.model.ReadingSummary
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

private const val ReadNumber = 7L

/** The read shown, as its route names it. */
private val Read = ReadId("7")

private val marks = ReadMarks(listOf(Highlight(MarkId("1"), "A line", null, "Chapter 1")), 1, emptyList(), 0)

private class FakeDetailHistory(override val capabilities: HistoryCapabilities = HistoryCapabilities.All) :
    HistoryRepository {
    val ratingCalls = mutableListOf<Int?>()
    val ratedReads = mutableListOf<ReadId>()
    var marksCalls = 0
    var failMarks = false
    var failRating = false

    /** When set, [setRating] waits for it, so a test can look at the state while the call is in flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun log(
        period: HistoryPeriod,
        outcomes: Set<ReadOutcome>,
        newestFirst: Boolean,
        offset: Int,
        limit: Int,
    ): ApiResult<ReadPage> = error("unused")

    override suspend fun summary(period: HistoryPeriod): ApiResult<ReadingSummary> = error("unused")

    override suspend fun marks(readId: ReadId): ApiResult<ReadMarks> {
        marksCalls++
        return if (failMarks) ApiResult.Failure(ApiError.ServerFailed) else ApiResult.Success(marks)
    }

    override suspend fun setRating(readId: ReadId, stars: Int?): ApiResult<RatingResult> {
        ratedReads += readId
        ratingCalls += stars
        gate?.await()
        return if (failRating) {
            ApiResult.Failure(ApiError.ServerFailed)
        } else {
            ApiResult.Success(RatingResult(stars, false))
        }
    }
}

class ReadDetailViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repo = FakeDetailHistory()
    private val cache = ReadRecordCache().apply { put(listOf(readRecord(ReadNumber, "Book", rating = 2))) }

    private fun viewModel(
        fake: FakeDetailHistory = repo,
        records: ReadRecordCache = cache,
        bookId: BookId? = null,
    ) = ReadDetailViewModel(fake, records, SavedStateHandle(mapOf("readId" to Read.value, "bookId" to bookId?.value)))

    @Test
    fun `it opens with the cached record, its rating and its marks`() = runTest {
        val state = viewModel().state.value

        assertThat(state.record).isEqualTo(cache.get(Read))
        assertThat(state.rating).isEqualTo(RatingUi.Editable(2))
        assertThat(state.marks).isEqualTo(MarksUi.Loaded(marks))
    }

    @Test
    fun `choosing a rating shows it at once and settles when saved`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = viewModel()
        viewModel.state.test {
            assertThat(awaitItem().rating).isEqualTo(RatingUi.Editable(2))

            viewModel.setStars(4)

            assertThat(awaitItem().rating).isEqualTo(RatingUi.Editable(4, saving = true))
            gate.complete(Unit)
            assertThat(awaitItem().rating).isEqualTo(RatingUi.Editable(4, saving = false))
        }
        assertThat(repo.ratingCalls).containsExactly(4)
        // The read the route names, from its text.
        assertThat(repo.ratedReads).containsExactly(Read)
    }

    @Test
    fun `a failed save restores the previous rating and flags the error`() = runTest {
        repo.failRating = true
        val viewModel = viewModel()

        viewModel.setStars(5)

        assertThat(viewModel.state.value.rating).isEqualTo(RatingUi.Editable(2, saving = false, error = true))
    }

    @Test
    fun `choosing the current rating again clears it`() = runTest {
        val cache = ReadRecordCache().apply { put(listOf(readRecord(ReadNumber, "Book", rating = 5))) }
        val viewModel = viewModel(records = cache)

        viewModel.setStars(5)

        assertThat(repo.ratingCalls).containsExactly(null)
        assertThat(viewModel.state.value.rating).isEqualTo(RatingUi.Editable(null))
    }

    @Test
    fun `a source without rating hides it and never saves one`() = runTest {
        val fake = FakeDetailHistory(HistoryCapabilities.All.copy(rating = false))
        val viewModel = viewModel(fake)

        viewModel.setStars(3)

        assertThat(viewModel.state.value.rating).isEqualTo(RatingUi.Hidden)
        assertThat(fake.ratingCalls).isEmpty()
    }

    @Test
    fun `a source without marks hides them and never asks for them`() = runTest {
        val fake = FakeDetailHistory(HistoryCapabilities.All.copy(marks = false))
        val viewModel = viewModel(fake)

        viewModel.retryMarks()

        assertThat(viewModel.state.value.marks).isEqualTo(MarksUi.Hidden)
        assertThat(fake.marksCalls).isEqualTo(0)
    }

    @Test
    fun `failing marks are an error until a retry works`() = runTest {
        repo.failMarks = true
        val viewModel = viewModel()
        viewModel.state.test {
            assertThat(awaitItem().marks).isEqualTo(MarksUi.Error)
            repo.failMarks = false

            viewModel.retryMarks()

            assertThat(awaitItem().marks).isEqualTo(MarksUi.Loading)
            assertThat(awaitItem().marks).isEqualTo(MarksUi.Loaded(marks))
        }
    }

    @Test
    fun `a cache miss leaves no record but the rating and the marks still work`() = runTest {
        val viewModel = viewModel(records = ReadRecordCache())

        val state = viewModel.state.value
        assertThat(state.record).isNull()
        assertThat(state.rating).isEqualTo(RatingUi.Editable(null))
        assertThat(state.marks).isEqualTo(MarksUi.Loaded(marks))

        viewModel.setStars(3)

        assertThat(viewModel.state.value.rating).isEqualTo(RatingUi.Editable(3))
    }

    @Test
    fun `a saved rating reaches the cache`() = runTest {
        val viewModel = viewModel()

        viewModel.setStars(4)

        assertThat(cache.get(Read)?.rating).isEqualTo(4)
    }

    @Test
    fun `a failed save leaves the cache alone`() = runTest {
        repo.failRating = true

        viewModel().setStars(4)

        assertThat(cache.get(Read)?.rating).isEqualTo(2)
    }

    @Test
    fun `without a cached record the book comes from the route`() = runTest {
        val state = viewModel(records = ReadRecordCache(), bookId = BookId("55")).state.value

        assertThat(state.record).isNull()
        assertThat(state.openBookId).isEqualTo(BookId("55"))
    }

    @Test
    fun `with a cached record the book is the record's`() = runTest {
        val openBookId = viewModel(bookId = BookId("55")).state.value.openBookId

        assertThat(openBookId).isEqualTo(cache.get(Read)?.bookId)
        assertThat(openBookId).isNotEqualTo(BookId("55"))
    }

    @Test
    fun `without a record or a route book there is nothing to open`() = runTest {
        assertThat(viewModel(records = ReadRecordCache()).state.value.openBookId).isNull()
    }
}
