package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [ComicSettingsStore] kept in memory. The direction of [initial] is the default one, which no
 * scope holds: a direction is set for a scope with [setDirection], or given in [directions]; a default given
 * to [settings] stands in for none, and is not kept. Likewise no scope is read panel by panel for what [initial]
 * says: it is turned on for a scope with [setPanels], or given in [panels].
 *
 * @param noticeSeen whether the device was already told what reading panel by panel is.
 */
class InMemoryComicSettingsStore(
    initial: ComicSettings = ComicSettings(),
    directions: Map<DirectionScope, Direction> = emptyMap(),
    panels: Set<DirectionScope> = emptySet(),
    noticeSeen: Boolean = false,
) : ComicSettingsStore {
    private data class Stored(
        val device: ComicSettings,
        val directions: Map<DirectionScope, Direction>,
        val panels: Set<DirectionScope>,
        val noticeSeen: Boolean,
        val told: Set<BookId> = emptySet(),
    )

    private val state = MutableStateFlow(
        Stored(
            device = initial.copy(direction = ComicSettings().direction, panels = false),
            directions = directions,
            panels = panels,
            noticeSeen = noticeSeen,
        ),
    )

    /** The directions remembered now. */
    val directions: Map<DirectionScope, Direction> get() = state.value.directions

    /** What is read panel by panel now. */
    val panels: Set<DirectionScope> get() = state.value.panels

    /** The albums the user was told lend themselves badly to it. */
    val told: Set<BookId> get() = state.value.told

    override fun settings(scope: DirectionScope, default: Direction?): Flow<ComicSettings> = state
        .map {
            it.device.copy(
                direction = it.directions[scope] ?: default ?: it.device.direction,
                panels = scope in it.panels,
            )
        }
        .distinctUntilChanged()

    override val panelNoticeSeen: Flow<Boolean> = state.map { it.noticeSeen }.distinctUntilChanged()

    override val shared: Flow<ComicSettings> = state.map { it.device }.distinctUntilChanged()

    override suspend fun setFit(fit: Fit) = state.update { it.copy(device = it.device.copy(fit = fit)) }

    override suspend fun setTwoPages(twoPages: TwoPages) =
        state.update { it.copy(device = it.device.copy(twoPages = twoPages)) }

    override suspend fun setBackground(background: Background) =
        state.update { it.copy(device = it.device.copy(background = background)) }

    override suspend fun setWholePageFirst(first: Boolean) =
        state.update { it.copy(device = it.device.copy(wholePageFirst = first)) }

    override suspend fun seePanelNotice() = state.update { it.copy(noticeSeen = true) }

    override suspend fun setDirection(scope: DirectionScope, direction: Direction) =
        state.update { it.copy(directions = it.directions + (scope to direction)) }

    override suspend fun setPanels(scope: DirectionScope, on: Boolean) =
        state.update { it.copy(panels = if (on) it.panels + scope else it.panels - scope) }

    override suspend fun albumTold(bookId: BookId): Boolean = bookId in state.value.told

    override suspend fun tellAlbum(bookId: BookId) = state.update { it.copy(told = it.told + bookId) }
}
