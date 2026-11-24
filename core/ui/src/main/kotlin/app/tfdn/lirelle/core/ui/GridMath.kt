package app.tfdn.lirelle.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WindowWidthClass

val GridSpacing: Dp = 12.dp

private val WideGridSpacing = 16.dp
private val PhoneMinCoverWidth = 104.dp
private val TabletMinCoverWidth = 160.dp

/** A wide window gets more room between covers as well as larger covers: a tight grid of many books is hard to read. */
fun gridSpacing(width: WindowWidthClass): Dp = if (width == WindowWidthClass.Expanded) WideGridSpacing else GridSpacing

/** As many columns as fit [minItemWidth], with [spacing] between them. */
fun columnsFor(available: Dp, minItemWidth: Dp, spacing: Dp, minColumns: Int = 2): Int =
    ((available + spacing) / (minItemWidth + spacing)).toInt().coerceAtLeast(minColumns)

/** Covers are larger on a tablet: the same count of pixels per book would be too many books. */
fun bookGridColumns(available: Dp, width: WindowWidthClass): Int = columnsFor(
    available = available,
    minItemWidth = if (width == WindowWidthClass.Expanded) TabletMinCoverWidth else PhoneMinCoverWidth,
    spacing = gridSpacing(width),
)

fun bookListColumns(width: WindowWidthClass): Int = if (width == WindowWidthClass.Expanded) 2 else 1

fun rowCount(items: Int, columns: Int): Int = (items + columns - 1) / columns
