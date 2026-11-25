package app.tfdn.lirelle.feature.library

import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.ServerLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest

/**
 * The pages of a list for one [library] of the server, null for all of them: [items] only ever carries that
 * library's items, under whatever sort and filters. A tab that shows another instance starts a new list — from
 * its top, loading, with nothing of the library left — where a new sort goes on in the list it has.
 */
class LibraryPages<T : Any> internal constructor(val library: ServerLibrary?, val items: Flow<PagingData<T>>)

/** A list as the repository gave it, and the library it was asked for. */
internal class LibraryList<T : Any>(val library: ServerLibrary?, val list: PagedList<T>)

/**
 * The pages of each library in turn, from [lists] in whatever order the two flows change: a list asked for
 * another library is never among the pages of this one. The pages of a library are let go when another is chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T : Any> Flow<ServerLibrary?>.pagesOf(lists: Flow<LibraryList<T>>): Flow<LibraryPages<T>> =
    transformLatest { library ->
        coroutineScope {
            val items = lists.filter { it.library == library }.flatMapLatest { it.list.items }.cachedIn(this)
            emit(LibraryPages(library, items))
            awaitCancellation()
        }
    }
