package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

/**
 * Server text that may contain HTML, as simply formatted text: paragraphs and emphasis are
 * kept, nothing is executed, and links are shown but not followed.
 */
fun simpleTextOf(html: String): AnnotatedString =
    AnnotatedString.fromHtml(html.trim(), linkInteractionListener = LinkInteractionListener { })

/** [html] cut to [collapsedLines] lines, with a button to open it when it is longer. */
@Composable
fun CollapsibleText(html: String, modifier: Modifier = Modifier, collapsedLines: Int = 5) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isCut by remember { mutableStateOf(false) }
    val text = remember(html) { simpleTextOf(html) }
    Column(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) isCut = it.hasVisualOverflow },
        )
        if (isCut || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                SingleLineLabel(stringResource(if (expanded) R.string.text_less else R.string.text_more))
            }
        }
    }
}
