package app.tfdn.lirelle.core.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreLibraryPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "library.preferences_pb")

    private fun open(file: File): Pair<DataStoreLibraryPreferences, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return DataStoreLibraryPreferences(PreferenceDataStoreFactory.create(scope = scope) { file }) to scope
    }

    @Test
    fun `a view starts as a grid with no stored sort`() = runBlocking {
        val (preferences, scope) = open(file())

        assertThat(preferences.view("books").first()).isEqualTo(ViewPreferences())
        assertThat(ViewPreferences().presentation).isEqualTo(Presentation.Grid)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `changes are kept per view and survive a restart`() = runBlocking {
        val file = file()
        val (preferences, scope) = open(file)

        preferences.update("books") { it.copy(presentation = Presentation.List, sort = "Title", descending = false) }
        preferences.update("series") { it.copy(sort = "UnreadCount", descending = true) }

        scope.coroutineContext[Job]!!.cancelAndJoin()
        val (reopened, secondScope) = open(file)
        assertThat(reopened.view("books").first())
            .isEqualTo(ViewPreferences(Presentation.List, sort = "Title", descending = false))
        assertThat(reopened.view("series").first())
            .isEqualTo(ViewPreferences(Presentation.Grid, sort = "UnreadCount", descending = true))
        assertThat(reopened.view("authors").first()).isEqualTo(ViewPreferences())
        secondScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun `an unreadable stored value falls back to the default`() = runBlocking {
        val file = file()
        val (preferences, scope) = open(file)
        preferences.update("books") { it.copy(presentation = Presentation.List) }
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val (reopened, secondScope) = open(file)
        reopened.corruptForTest("books")

        assertThat(reopened.view("books").first()).isEqualTo(ViewPreferences())
        secondScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
