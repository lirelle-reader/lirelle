package app.tfdn.lirelle.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity

/** The space between two words of a label, as a share of the size of its text. */
private const val WordGapEm = 0.28f

/**
 * The label of a button that grows with it: each word is a text of one line, and the words that do not fit go
 * on further lines. The button gives a screen reader the label whole.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordsLabel(label: String) {
    val gap = with(LocalDensity.current) { (LocalTextStyle.current.fontSize * WordGapEm).toDp() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
        // A no-break space is not a place to cut: "(42 %)" stays in one piece.
        label.split(' ').forEach { word -> SingleLineLabel(word) }
    }
}
