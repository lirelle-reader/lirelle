package app.tfdn.lirelle.feature.history

import app.tfdn.lirelle.core.history.model.HistoryCapabilities
import app.tfdn.lirelle.core.history.model.ReadMarks
import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.ids.BookId

/** The rating of a read, as the detail screen shows it. */
sealed interface RatingUi {
    /** The source has no rating capability. */
    data object Hidden : RatingUi

    /** [stars] is null when the read is not rated; [error] means the last change did not go through. */
    data class Editable(val stars: Int?, val saving: Boolean = false, val error: Boolean = false) : RatingUi
}

/** The highlights and journal of a read. */
sealed interface MarksUi {
    /** The source has no marks capability. */
    data object Hidden : MarksUi

    data object Loading : MarksUi

    data class Loaded(val marks: ReadMarks) : MarksUi

    data object Error : MarksUi
}

/**
 * The read-detail screen. [record] is null only when the read was not in the record cache; [openBookId] is the
 * book the "Open book page" button leads to: the record's, else the navigation argument's.
 */
data class ReadDetailUiState(
    val capabilities: HistoryCapabilities,
    val record: ReadRecord?,
    val openBookId: BookId?,
    val rating: RatingUi,
    val marks: MarksUi,
)
