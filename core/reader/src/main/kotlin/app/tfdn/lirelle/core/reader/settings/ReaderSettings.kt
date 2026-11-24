package app.tfdn.lirelle.core.reader.settings

enum class ReaderTheme { System, Light, Sepia, Dark, Black }

/** [Book] imposes nothing: the book's own fonts, or what its style sheet asks for. */
enum class ReaderFont { Book, Serif, Sans, Dyslexic }

enum class ReaderAlign { Book, Justify, Start }

enum class ReaderFlow { Paginated, Scrolled }

/** The side of the screen where sliding a finger changes the brightness. */
enum class BrightnessEdge { Left, Right, Off }

enum class MarginSize { Narrow, Medium, Wide }

/** What one end of the header or of the footer shows. */
enum class RunningSlot {
    Nothing,
    BookTitle,
    ChapterTitle,
    BookPercent,
    ChapterPercent,
    PagesLeftInChapter,
    Time,
    Battery,
}

data class RunningLine(val left: RunningSlot, val right: RunningSlot)

/** The text sizes offered, in CSS pixels. The minus and plus buttons move along this list. */
val FontSizesPx: List<Int> = listOf(12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 24, 26, 28, 32)

/** The line spacings offered, as multiples of the text size. */
val LineHeights: List<Double> = listOf(1.0, 1.15, 1.3, 1.4, 1.5, 1.6, 1.75, 2.0)

/** 18 pixels. */
const val DefaultFontStep = 6

/** One and a half. */
const val DefaultLineStep = 4

/** The window's brightness; the lowest value is not 0, which some devices take as "screen off". */
val BrightnessRange: ClosedFloatingPointRange<Float> = 0.01f..1f

/** The colour a new highlight gets until another is chosen: equals `HighlightColour.Yellow.hex`. */
const val DefaultHighlightColour = "#FACC15"

private val ColourPattern = Regex("#[0-9A-Fa-f]{6}")

/**
 * How books are shown. One set for every book, kept on the device.
 *
 * @property fontSizeStep index into [FontSizesPx].
 * @property lineHeightStep index into [LineHeights].
 * @property brightness within [BrightnessRange]; null follows the system.
 * @property footnotePanel notes open in a panel over the page rather than jumping to them.
 * @property cornerBookmark a tap on the page's top corner marks the page.
 * @property lastHighlightColour `#RRGGBB`, the colour of the last highlight made.
 */
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.System,
    val font: ReaderFont = ReaderFont.Book,
    val fontSizeStep: Int = DefaultFontStep,
    val lineHeightStep: Int = DefaultLineStep,
    val margins: MarginSize = MarginSize.Medium,
    val align: ReaderAlign = ReaderAlign.Book,
    val hyphenate: Boolean = true,
    val flow: ReaderFlow = ReaderFlow.Paginated,
    val volumeKeys: Boolean = false,
    val brightnessEdge: BrightnessEdge = BrightnessEdge.Left,
    val brightness: Float? = null,
    val header: RunningLine = RunningLine(RunningSlot.ChapterTitle, RunningSlot.Time),
    val footer: RunningLine = RunningLine(RunningSlot.PagesLeftInChapter, RunningSlot.BookPercent),
    val footnotePanel: Boolean = true,
    val cornerBookmark: Boolean = true,
    val lastHighlightColour: String = DefaultHighlightColour,
)

/** These settings with the steps and the brightness brought back within their bounds. */
fun ReaderSettings.normalized(): ReaderSettings = copy(
    fontSizeStep = fontSizeStep.coerceIn(FontSizesPx.indices),
    lineHeightStep = lineHeightStep.coerceIn(LineHeights.indices),
    brightness = brightness?.takeUnless { it.isNaN() }?.coerceIn(BrightnessRange),
    lastHighlightColour = lastHighlightColour.takeIf { ColourPattern.matches(it) }?.uppercase()
        ?: DefaultHighlightColour,
)
