package app.tfdn.lirelle.feature.reader.text

/** Offers [offered] and notes each action performed with its text, oldest first. */
class FakeTextActions : TextActions {
    var offered: List<TextAction> = listOf(TextAction.Copy, TextAction.Share, TextAction.SearchWeb)
    val performed = mutableListOf<Pair<TextAction, String>>()

    /** How many times what the device offers was asked. */
    var asked = 0
        private set

    override fun available(): List<TextAction> {
        asked++
        return offered
    }

    override fun perform(action: TextAction, text: String): Boolean {
        performed += action to text
        return true
    }
}
