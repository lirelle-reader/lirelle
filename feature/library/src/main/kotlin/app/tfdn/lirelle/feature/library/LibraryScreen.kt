package app.tfdn.lirelle.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.ui.OnShown
import app.tfdn.lirelle.core.ui.ScreenTitleRow
import app.tfdn.lirelle.feature.library.authors.AuthorsTab
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListScreen
import app.tfdn.lirelle.feature.library.series.SeriesTab
import app.tfdn.lirelle.feature.library.shelves.ShelvesTab
import kotlinx.coroutines.launch

/**
 * The Library destination with its real tabs: those the open profile's source offers.
 *
 * @param titleAction what stands at the end of the title's row: the profile button, which the app gives.
 */
@Composable
fun LibraryRoute(
    onOpenBook: (BookId) -> Unit,
    onOpenSeries: (SeriesId) -> Unit,
    onOpenAuthor: (AuthorId) -> Unit,
    onOpenShelf: (Shelf) -> Unit,
    modifier: Modifier = Modifier,
    titleAction: @Composable () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    // The libraries of the server may have changed while the Library was not looked at.
    OnShown(viewModel::refresh)
    LibraryScreen(
        modifier = modifier,
        tabs = viewModel.tabs,
        startTab = viewModel.startTab,
        libraries = libraries,
        onChooseLibrary = viewModel::onChoose,
        titleAction = titleAction,
    ) { tab ->
        when (tab) {
            LibraryTab.Books -> BookListScreen(
                scope = BookListScope.library(),
                onOpenBook = onOpenBook,
                emptyText = stringResource(R.string.books_empty),
            )
            LibraryTab.Series -> SeriesTab(onOpenSeries = onOpenSeries)
            LibraryTab.Authors -> AuthorsTab(onOpenAuthor = onOpenAuthor)
            LibraryTab.Shelves -> ShelvesTab(onOpenShelf = onOpenShelf)
        }
    }
}

/**
 * The title, the tabs and a pager; swiping moves between tabs.
 *
 * @param tabs the tabs shown, in their order. Should they change, the Library starts again on [startTab]: a page
 *   kept by its place could otherwise be another tab's.
 * @param startTab the tab the Library opens on, the source's (the first when it is not among [tabs]). Only a
 *   Library shown for the first time opens there: the tab the user left comes back with the pager's saved state.
 * @param libraries the libraries of the server and the one shown: from two libraries, the title chooses among them.
 *   Another choice leaves the tab shown as it is; the lists of the tabs start again by themselves.
 * @param onChooseLibrary the library chosen in the title's menu, null for all of them.
 * @param titleAction what stands at the end of the title's row, centred on the title as far as the tabs
 *   under it allow; the row is as high with it as without.
 * @param page the content of a tab. The pager keeps the state of the pages the user leaves.
 */
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    tabs: List<LibraryTab> = LibraryTab.entries,
    startTab: LibraryTab = LibraryTab.Books,
    libraries: LibraryChoiceState = LibraryChoiceState(),
    onChooseLibrary: (ServerLibrary?) -> Unit = {},
    titleAction: @Composable () -> Unit = {},
    page: @Composable (LibraryTab) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        ScreenTitleRow(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = TitleToTabs),
            // The tabs are right under: the action stays clear of them, and so does the title when it is a button.
            roomBelow = TitleToTabs,
            action = titleAction,
        ) { LibraryTitle(libraries, onChooseLibrary, roomBelow = TitleToTabs) }
        // Other tabs are another pager: a page kept by its place could otherwise be another tab's. Keyed by the
        // tabs' names, whose hash is the same in every process (an enum's is not), so the saved tab and pages
        // come back after the process was stopped.
        key(tabs.joinToString { it.name }) { TabbedPages(tabs, startTab, page) }
    }
}

/** The tabs over their pager, in the Library's column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabbedPages(tabs: List<LibraryTab>, startTab: LibraryTab, page: @Composable (LibraryTab) -> Unit) {
    // The start tab is only the pager's first page: a saved page, the tab the user left, wins over it.
    val pagerState = rememberPagerState(initialPage = tabs.indexOf(startTab).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    // The tabs share the width: a row that scrolls sideways would cut the labels past
    // its edge. A label too long for its tab ends with an ellipsis.
    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
        tabs.forEachIndexed { index, tab ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                modifier = Modifier.testTag("tab-${tab.name}"),
            ) { TabLabel(stringResource(tab.label)) }
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) { index ->
        // A page is as large as the pager, whatever its content asks for.
        Box(modifier = Modifier.fillMaxSize(), propagateMinConstraints = true) { page(tabs[index]) }
    }
}

/**
 * The label of a tab, with less room lost at its sides than the standard tab: at a large
 * text size a quarter of a phone's width is narrow, and the label keeps its size.
 */
@Composable
private fun TabLabel(text: String) {
    Box(
        modifier = Modifier
            .heightIn(min = TabHeight)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProvideTextStyle(MaterialTheme.typography.titleSmall) { SingleLineLabel(text) }
    }
}

private val TabHeight = 48.dp

/** Between the title and the tabs. */
private val TitleToTabs = 4.dp
