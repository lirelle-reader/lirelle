package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.TocEntry
import kotlinx.coroutines.launch

private const val Hundred = 100.0

internal class Navigation(private val core: ReaderCore) : ReaderNavigation {

    override fun nextChapter() = chapter(step = 1)

    override fun previousChapter() = chapter(step = -1)

    override fun nextPage() = core.turn(forward = true)

    override fun previousPage() = core.turn(forward = false)

    override fun goToToc(entry: TocEntry) {
        core.update { it.copy(openSheet = null) }
        core.jump(HostMessage.GoTo.Href(entry.href), remember = true)
    }

    override fun seekTo(fraction: Double) {
        if (fraction.isNaN()) return
        core.jump(HostMessage.GoTo.Fraction(fraction.coerceIn(0.0, 1.0)), remember = true)
    }

    override fun goBack() {
        if (!core.driver.opened) return
        val place = core.goBack.pop() ?: return
        core.jump(HostMessage.GoTo.Cfi(place), remember = false)
        core.returning = core.expected && core.goBack.isEmpty
    }

    /** The entry of the table of contents after or before the current one; the first one from before any. */
    private fun chapter(step: Int) {
        val toc = core.current.toc
        val here = toc.indexOfFirst { it.id == core.current.location?.tocId }
        val target = toc.getOrNull(here + step) ?: return
        core.jump(HostMessage.GoTo.Href(target.href), remember = false)
    }
}

internal class Chrome(
    private val core: ReaderCore,
    private val appearance: Appearance,
    private val opening: Opening,
    private val marks: MarksPart,
) : ReaderChrome {

    override fun toggleControls() = core.update { it.copy(controlsShown = !it.controlsShown) }

    /** The panel of contents, bookmarks and notes shows the marks read again, on the tab last used. */
    override fun showSheet(sheet: ReaderSheet) {
        core.update { it.copy(openSheet = sheet) }
        if (sheet == ReaderSheet.Contents) marks.onPanelOpened()
    }

    override fun dismissSheet() = core.update { it.withoutSheet() }

    override fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = appearance.update(transform)

    override fun setBrightness(level: Float) = appearance.setBrightness(level)

    override fun keepBrightness() = appearance.keepBrightness()

    override fun retry() = opening.retry()

    override fun leave() {
        core.events.trySend(ReaderEvent.Leave)
    }
}

internal class Prompts(private val core: ReaderCore) : ReaderPrompts {

    /** Whatever this device still had to send is dropped first: the user chose the other position. */
    override fun acceptNewerPosition() {
        val offered = takeOffer() ?: return
        core.scope.launch {
            core.session?.accept(offered)
            core.jump(target(offered), remember = true)
        }
    }

    override fun ignoreNewerPosition() {
        val offered = takeOffer() ?: return
        core.session?.ignore(offered)
    }

    override fun confirmExternalLink() {
        val address = core.current.externalLink ?: return
        dismissExternalLink()
        core.events.trySend(ReaderEvent.OpenUrl(address))
    }

    override fun dismissExternalLink() = core.update { it.copy(externalLink = null) }

    private fun takeOffer(): ReadingPosition? {
        val offered = core.offered
        core.offered = null
        core.update { it.copy(newerPosition = null) }
        return offered
    }

    private fun target(offered: ReadingPosition): HostMessage.GoTo =
        offered.cfi?.let(HostMessage.GoTo::Cfi)
            ?: HostMessage.GoTo.Fraction((offered.percent / Hundred).coerceIn(0.0, 1.0))
}
