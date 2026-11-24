package app.tfdn.lirelle.core.downloads.update

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.isUpdating
import app.tfdn.lirelle.core.downloads.store.DataStores
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.network.ApiError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

private val Book1 = BookId("1")
private val Book2 = BookId("2")
private val Book3 = BookId("3")
private val Book4 = BookId("4")

class DownloadUpdatesTest {

    @get:Rule
    val dataStores = DataStores()

    private fun updatesTest(test: suspend UpdateFixture.() -> Unit) = runBlocking { UpdateFixture(dataStores).test() }

    private fun UpdateFixture.pending(version: String = "v2") =
        FormatUpdate(version, listOf(epub(version = version)), bytes = 40)

    @Test
    fun `within the hour of a full pass, another is not made unless forced`() = updatesTest {
        keep(Book1, complete(Text, epub()))
        serve(Book1, Text, epub())
        updates.check()
        plans.asked.clear()

        later(minutes = 60)
        updates.check()
        assertThat(plans.asked).isEmpty()

        updates.check(force = true)
        assertThat(plans.asked).hasSize(1)
    }

    @Test
    fun `more than an hour after a full pass, another is made`() = updatesTest {
        keep(Book1, complete(Text, epub()))
        serve(Book1, Text, epub())
        updates.check()
        plans.asked.clear()

        later(minutes = 61)
        updates.check()

        assertThat(plans.asked).hasSize(1)
    }

    @Test
    fun `a pass in which a book was not found moves the hour as one that found it`() = updatesTest {
        keep(Book1, complete(Text, epub()))
        fail(Book1, Text, ApiError.NotFound)

        updates.check()

        assertThat(clock.last()).isEqualTo(now)
    }

    @Test
    fun `a pass in which the server answered for no book leaves the hour, and the next one asks again`() =
        updatesTest {
            keep(Book1, complete(Text, epub()))
            keep(Book2, complete(Text, epub("13.epub")))
            fail(Book1, Text, ApiError.ServerUnreachable)
            fail(Book2, Text, ApiError.ServerFailed)

            updates.check()
            assertThat(clock.last()).isNull()

            serve(Book1, Text, epub())
            later(minutes = 1)
            updates.check()
            assertThat(plans.asked).hasSize(4)
            assertThat(clock.last()).isEqualTo(now)
        }

    @Test
    fun `a pass with no book kept moves the hour`() = updatesTest {
        updates.check()

        assertThat(clock.last()).isEqualTo(now)
    }

    @Test
    fun `the check of one book does not move the hour`() = updatesTest {
        keep(Book1, complete(Text, epub()))
        serve(Book1, Text, epub())

        updates.checkBook(Book1)

        assertThat(clock.last()).isNull()
    }

    @Test
    fun `offline nothing is checked and the hour is not moved`() = updatesTest {
        online = false
        keep(Book1, complete(Text, epub()))
        serve(Book1, Text, epub(version = "v2"), version = "v2")

        updates.check(force = true)
        updates.checkBook(Book1)

        assertThat(plans.asked).isEmpty()
        assertThat(clock.last()).isNull()
        assertThat(update(Book1, Text)).isNull()
    }

    @Test
    fun `an hour stored with an owner field still reads, and is written again without it`() = updatesTest {
        val key = stringPreferencesKey("update_check")
        dataStore.edit {
            it[key] = """{"owner":{"serverUrl":"https://books.example","userId":7},"atMillis":${now.toEpochMilli()}}"""
        }

        assertThat(clock.last()).isEqualTo(now)

        later(minutes = 5)
        clock.mark(now)
        assertThat(clock.last()).isEqualTo(now)
        assertThat(dataStore.data.first()[key]).doesNotContain("owner")
    }

    @Test
    fun `an account that may not download is refused its updates`() = updatesTest {
        keep(Book1, complete(Text, epub(), update = pending()))
        canDownload.value = false

        assertThat(updates.update(Book1)).isEqualTo(AskRefusal.NotAllowed)
        assertThat(updates.updateAll()).isEqualTo(AskRefusal.NotAllowed)

        assertThat(update(Book1, Text)).isEqualTo(pending())
    }

    @Test
    fun `updating a book queues its pending updates, and no other`() = updatesTest {
        val failed = pending().copy(state = FormatState.Failed(DownloadFailure.Damaged, 0))
        keep(Book1, complete(Text, epub(), update = pending()), complete(Audiobook, audio("a1"), update = failed))
        keep(Book2, complete(Text, epub(), update = pending()))

        assertThat(updates.update(Book1)).isNull()

        assertThat(update(Book1, Text)).isEqualTo(pending().copy(state = FormatState.Queued))
        assertThat(update(Book1, Audiobook)).isEqualTo(failed)
        assertThat(update(Book2, Text)).isEqualTo(pending())
        assertThat(book(Book1).isUpdating).isTrue()
    }

    @Test
    fun `updating all queues every pending update, but for a book deleted at close`() = updatesTest {
        keep(Book1, complete(Text, epub(), update = pending()))
        keep(Book2, complete(Text, epub(), update = pending("v3")))
        keep(Book3, complete(Text, epub(), update = pending())) { it.copy(deleteAtClose = true) }
        keep(Book4, complete(Text, epub()))

        assertThat(updates.updateAll()).isNull()

        assertThat(update(Book1, Text)?.state).isEqualTo(FormatState.Queued)
        assertThat(update(Book2, Text)?.state).isEqualTo(FormatState.Queued)
        assertThat(update(Book3, Text)?.state).isNull()
        assertThat(update(Book4, Text)).isNull()
    }

    @Test
    fun `a book the server no longer has is not updated, alone or with the others`() = updatesTest {
        keep(Book1, complete(Text, epub(), update = pending())) { it.copy(gone = true) }

        assertThat(updates.update(Book1)).isNull()
        assertThat(update(Book1, Text)?.state).isNull()

        assertThat(updates.updateAll()).isNull()
        assertThat(update(Book1, Text)?.state).isNull()
    }
}
