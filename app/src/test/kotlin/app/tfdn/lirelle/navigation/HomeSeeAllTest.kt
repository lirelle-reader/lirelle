package app.tfdn.lirelle.navigation

import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.profile.AbsCapabilities
import app.tfdn.lirelle.profile.KavitaCapabilities
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** What "See all" of a Home row opens: the row's own screen, or a list of the library's books. */
class HomeSeeAllTest {

    private val withoutBooks = SourceCapabilities.Everything.copy(libraryBooks = false)

    @Test
    fun `Continue and Up next always open their row`() {
        listOf(SourceCapabilities.Everything, withoutBooks).forEach { capabilities ->
            assertThat(HomeRowKind.Continue.seeAllAsRow(capabilities)).isTrue()
            assertThat(HomeRowKind.UpNext.seeAllAsRow(capabilities)).isTrue()
        }
    }

    @Test
    fun `Want to read and Recently added are lists when the source can list its books`() {
        assertThat(HomeRowKind.WantToRead.seeAllAsRow(SourceCapabilities.Everything)).isFalse()
        assertThat(HomeRowKind.RecentlyAdded.seeAllAsRow(SourceCapabilities.Everything)).isFalse()
    }

    @Test
    fun `Want to read and Recently added open their row when the source cannot list its books`() {
        assertThat(HomeRowKind.WantToRead.seeAllAsRow(withoutBooks)).isTrue()
        assertThat(HomeRowKind.RecentlyAdded.seeAllAsRow(withoutBooks)).isTrue()
    }

    @Test
    fun `Want to read and Recently added open their row when the source always shows one library`() {
        // Its row spans every library, newest first; a list of the books would show one library alone.
        val oneLibrary = SourceCapabilities.Everything.copy(libraryAlwaysChosen = true)

        assertThat(HomeRowKind.WantToRead.seeAllAsRow(oneLibrary)).isTrue()
        assertThat(HomeRowKind.RecentlyAdded.seeAllAsRow(oneLibrary)).isTrue()
    }

    @Test
    fun `each source's Recently added opens what it did, Audiobookshelf's its row`() {
        val opensRow = mapOf(
            "BookOrbit" to SourceCapabilities.Everything,
            "Komga" to KomgaLikeCapabilities,
            "Kavita" to KavitaCapabilities,
            "Audiobookshelf" to AbsCapabilities,
        ).mapValues { (_, capabilities) -> HomeRowKind.RecentlyAdded.seeAllAsRow(capabilities) }

        assertWithMessage("Recently added opens its row").that(opensRow).containsExactly(
            "BookOrbit", false,
            "Komga", false,
            "Kavita", true,
            "Audiobookshelf", true,
        )
    }

    @Test
    fun `the view model answers for the open profile's source`() {
        assertThat(HomeListViewModel(withoutBooks).seesAllAsRow(HomeRowKind.WantToRead)).isTrue()
        assertThat(HomeListViewModel(SourceCapabilities.Everything).seesAllAsRow(HomeRowKind.WantToRead)).isFalse()
    }
}
