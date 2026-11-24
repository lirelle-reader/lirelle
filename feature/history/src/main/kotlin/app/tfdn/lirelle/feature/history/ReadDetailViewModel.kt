package app.tfdn.lirelle.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.history.HistoryRepository
import app.tfdn.lirelle.core.history.ReadRecordCache
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ReadIdKey = "readId"
private const val BookIdKey = "bookId"

/**
 * One read: its record (from the cache, null on a miss), its rating, editable, and its highlights and journal.
 * The read id comes from the navigation argument `readId`.
 */
@HiltViewModel
class ReadDetailViewModel @Inject constructor(
    private val repo: HistoryRepository,
    private val cache: ReadRecordCache,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // The route carries the id's text: a SavedStateHandle cannot hold the id itself.
    private val readId = ReadId(checkNotNull(savedStateHandle.get<String>(ReadIdKey)) { "No read to show" })
    private val capabilities = repo.capabilities
    private val record = cache.get(readId)

    // The book's id comes as its text too, absent, null or empty when the book is unknown: read it defensively.
    private val navBookId: BookId? =
        (savedStateHandle.get<Any>(BookIdKey) as? String)?.takeIf { it.isNotEmpty() }?.let(::BookId)

    private val mutableState = MutableStateFlow(
        ReadDetailUiState(
            capabilities = capabilities,
            record = record,
            openBookId = record?.bookId ?: navBookId,
            rating = if (capabilities.rating) RatingUi.Editable(record?.rating) else RatingUi.Hidden,
            marks = if (capabilities.marks) MarksUi.Loading else MarksUi.Hidden,
        ),
    )
    val state: StateFlow<ReadDetailUiState> = mutableState.asStateFlow()

    init {
        loadMarks()
    }

    /** Sets the rating to [n] stars, or clears it when [n] is already the current value. */
    fun setStars(n: Int) {
        val current = state.value.rating as? RatingUi.Editable ?: return
        val target = if (n == current.stars) null else n
        mutableState.update { it.copy(rating = RatingUi.Editable(target, saving = true)) }
        viewModelScope.launch {
            val next = when (val result = repo.setRating(readId, target)) {
                is ApiResult.Success -> {
                    cache.updateRating(readId, result.value.rating)
                    RatingUi.Editable(result.value.rating)
                }
                is ApiResult.Failure -> RatingUi.Editable(current.stars, error = true)
            }
            mutableState.update { it.copy(rating = next) }
        }
    }

    fun retryMarks() = loadMarks()

    private fun loadMarks() {
        if (!capabilities.marks) return
        mutableState.update { it.copy(marks = MarksUi.Loading) }
        viewModelScope.launch {
            val next = when (val result = repo.marks(readId)) {
                is ApiResult.Success -> MarksUi.Loaded(result.value)
                is ApiResult.Failure -> MarksUi.Error
            }
            mutableState.update { it.copy(marks = next) }
        }
    }
}
