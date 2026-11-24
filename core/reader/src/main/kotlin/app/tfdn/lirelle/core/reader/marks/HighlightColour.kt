package app.tfdn.lirelle.core.reader.marks

private val SixDigits = Regex("#[0-9a-fA-F]{6}")

/** The colours the app offers. The server may hold others: [display] says how to draw any of them. */
enum class HighlightColour(val hex: String) {
    Yellow("#FACC15"),
    Green("#4ADE80"),
    Blue("#38BDF8"),
    Pink("#F472B6"),
    ;

    companion object {
        /** Any stored value as a colour to draw: `#RRGGBB` upper-cased, yellow for anything else. */
        fun display(stored: String): String = if (SixDigits.matches(stored)) stored.uppercase() else Yellow.hex
    }
}
