package app.tfdn.lirelle.feature.reader.text

/** What can be done with the selected text once it leaves the page. */
sealed interface TextAction {
    data object Translate : TextAction
    data object Define : TextAction
    data object Copy : TextAction
    data object Share : TextAction
    data object SearchWeb : TextAction

    /** Another app that acts on text, under its own name. */
    data class App(val label: String, val packageName: String, val activityName: String) : TextAction
}
