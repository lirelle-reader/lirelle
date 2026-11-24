package app.tfdn.lirelle.feature.account.switcher

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.tfdn.lirelle.core.ui.GripSheet
import app.tfdn.lirelle.feature.account.R

const val ProfilesMenuTag = "profiles-menu"

private val SheetEdge = 24.dp
private val MenuEdge = 20.dp
private val MenuWidth = 380.dp
private val MenuCorner = 16.dp

/** The most a popup's window leaves room for around what it holds. */
private val MenuShadow = 8.dp

/** Kept between the menu and the edges of the window, past what the system's bars take. */
private val MenuMargin = 16.dp

/**
 * What holds the list of profiles the profile button opens.
 */
sealed interface ProfilesHost {
    /** A sheet at the bottom of the screen: where the button is in a screen's title, on a phone. */
    data object Sheet : ProfilesHost

    /**
     * A menu beside the button, opening upwards from it: where the button is at the bottom of the rail.
     *
     * @property gap between the button and the menu: what is left of the rail past the button, and the
     *   space kept between the rail and the menu.
     */
    data class Menu(val gap: Dp) : ProfilesHost
}

/**
 * The list in a bottom sheet with a grip: closed by the grip, a tap above it and Back. The list scrolls when
 * it is taller than the screen leaves room for — many profiles, or a large text —, and ends above the
 * system's navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfilesSheet(state: SwitcherState, actions: ProfilesActions, onDismiss: () -> Unit) {
    val ground = BottomSheetDefaults.ContainerColor
    GripSheet(onDismissRequest = onDismiss, containerColor = ground) {
        ProfilesList(
            state = state,
            actions = actions,
            ground = ground,
            edge = SheetEdge,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        )
    }
}

/**
 * The list in a menu anchored to what this is composed in — the profile button —, placed by [MenuPosition].
 * A window of its own that takes the focus: a tap outside and Back close it, and a keyboard or a screen
 * reader, which is told its title, moves inside it. It scrolls when the window is not high enough for it.
 */
@Composable
internal fun ProfilesMenu(state: SwitcherState, actions: ProfilesActions, onDismiss: () -> Unit, gap: Dp) {
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing
    val window = LocalWindowInfo.current.containerSize
    val margin = with(density) { MenuMargin.roundToPx() }
    val top = safe.getTop(density) + margin
    val bottom = safe.getBottom(density) + margin
    val position = remember(density, gap, top) { MenuPosition(with(density) { gap.roundToPx() }, top, margin) }
    val title = stringResource(R.string.profiles_title)
    val ground = MaterialTheme.colorScheme.surfaceContainerLow
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = MenuWidth)
                .heightIn(max = with(density) { (window.height - top - bottom).coerceAtLeast(0).toDp() })
                .semantics { paneTitle = title }
                .testTag(ProfilesMenuTag),
            shape = RoundedCornerShape(MenuCorner),
            color = ground,
            shadowElevation = MenuShadow,
        ) {
            ProfilesList(
                state = state,
                actions = actions,
                ground = ground,
                edge = MenuEdge,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * Where the menu of profiles goes: beside its anchor, on the side the layout ends at — away from the rail
 * —, its bottom level with the anchor's, so that it opens upwards from the bottom of the rail. Kept inside
 * the window: never above [top], nor past the sides.
 *
 * @param gap between the anchor and the menu, in pixels.
 * @param top the highest the menu may start: under the system's bar, in pixels.
 * @param margin kept between the menu and the sides of the window, in pixels.
 */
internal class MenuPosition(private val gap: Int, private val top: Int, private val margin: Int) :
    PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val beside = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.right + gap
            LayoutDirection.Rtl -> anchorBounds.left - gap - popupContentSize.width
        }
        val farthest = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        return IntOffset(
            x = beside.coerceIn(margin, farthest),
            y = (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(top),
        )
    }
}
