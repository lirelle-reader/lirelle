package app.tfdn.lirelle.feature.player.car

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val Authority = "app.tfdn.lirelle.covers"
private const val NineHoursTwelve = (9 * 60 + 12) * 60_000L
private const val FortyMinutes = 40 * 60_000L

/** What the car is handed for a tab, a book and a series: the item Media3 gives it, in both languages. */
@RunWith(AndroidJUnit4::class)
class CarItemsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val items get() = CarItems(context.resources, Authority, profileId = "profile-1")

    private fun book(line: CarLine? = null, section: CarSection? = null): MediaItem =
        items.of(CarNode.Book(BookId("42"), "The Long Road", line, section))

    private fun MediaItem.groupTitle(): String? =
        mediaMetadata.extras?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)

    private fun MediaItem.asksGridOfBooksAndListOfFolders() {
        val extras = checkNotNull(mediaMetadata.extras)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    // --- the root

    @Test
    fun `the root is a folder that asks for books as a grid and folders as a list`() {
        val root = items.root()

        assertThat(root.mediaId).isEqualTo("root")
        assertThat(root.mediaMetadata.isBrowsable).isTrue()
        assertThat(root.mediaMetadata.isPlayable).isFalse()
        assertThat(items.rootParams.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        assertThat(items.rootParams.extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    // --- a tab

    @Test
    fun `a tab is a folder to browse, with its id, its name and its content styles`() {
        val tab = items.of(CarNode.Tab(CarPlace.Tab.Downloaded))

        assertThat(tab.mediaId).isEqualTo("tab/downloaded")
        assertThat(tab.mediaMetadata.isBrowsable).isTrue()
        assertThat(tab.mediaMetadata.isPlayable).isFalse()
        assertThat(tab.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        assertThat(tab.mediaMetadata.title.toString()).isEqualTo("Downloaded")
        tab.asksGridOfBooksAndListOfFolders()
    }

    @Test
    fun `the tabs are named in English`() {
        assertThat(CarPlace.Tab.entries.map { items.of(CarNode.Tab(it)).mediaMetadata.title.toString() })
            .containsExactly("Home", "Downloaded", "Audiobooks", "Series").inOrder()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the tabs are named in French`() {
        assertThat(CarPlace.Tab.entries.map { items.of(CarNode.Tab(it)).mediaMetadata.title.toString() })
            .containsExactly("Accueil", "Téléchargés", "Livres audio", "Séries").inOrder()
    }

    @Test
    fun `each tab has an icon of its own, a drawable of the app the car can load`() {
        val icons = CarPlace.Tab.entries.map { checkNotNull(items.of(CarNode.Tab(it)).mediaMetadata.artworkUri) }

        assertThat(icons.toSet()).hasSize(CarPlace.Tab.entries.size)
        icons.forEach { icon ->
            assertThat(icon.scheme).isEqualTo("android.resource")
            assertThat(icon.authority).isEqualTo(context.packageName)
            val (type, name) = icon.pathSegments
            assertThat(type).isEqualTo("drawable")
            assertThat(context.resources.getIdentifier(name, type, icon.authority)).isNotEqualTo(0)
        }
    }

    // --- a book

    @Test
    fun `a book is played, not browsed, with its id, its title and the cover the app serves`() {
        val book = book()

        assertThat(book.mediaId).isEqualTo("book/42")
        assertThat(book.mediaMetadata.isPlayable).isTrue()
        assertThat(book.mediaMetadata.isBrowsable).isFalse()
        assertThat(book.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        assertThat(book.mediaMetadata.title.toString()).isEqualTo("The Long Road")
        assertThat(book.mediaMetadata.artworkUri).isEqualTo(Uri.parse("content://$Authority/profile-1/42"))
    }

    @Test
    fun `a book without a line has its title alone, and stands in no group outside Home`() {
        assertThat(book().mediaMetadata.subtitle).isNull()
        assertThat(book().groupTitle()).isNull()
    }

    @Test
    fun `a book's line in English`() {
        assertThat(book(CarLine.Left(NineHoursTwelve)).mediaMetadata.subtitle.toString()).isEqualTo("9h 12m left")
        assertThat(book(CarLine.Left(FortyMinutes)).mediaMetadata.subtitle.toString()).isEqualTo("40 min left")
        assertThat(book(CarLine.InSeries("A Saga", "2")).mediaMetadata.subtitle.toString()).isEqualTo("A Saga #2")
        assertThat(book(CarLine.InSeries("A Saga", null)).mediaMetadata.subtitle.toString()).isEqualTo("A Saga")
        assertThat(book(CarLine.Author("Ann Author")).mediaMetadata.subtitle.toString()).isEqualTo("Ann Author")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a book's line in French`() {
        assertThat(book(CarLine.Left(NineHoursTwelve)).mediaMetadata.subtitle.toString()).isEqualTo("Reste 9 h 12")
        assertThat(book(CarLine.Left(FortyMinutes)).mediaMetadata.subtitle.toString()).isEqualTo("Reste 40 min")
        assertThat(book(CarLine.InSeries("A Saga", "2")).mediaMetadata.subtitle.toString()).isEqualTo("A Saga nº 2")
        assertThat(book(CarLine.Author("Ann Author")).mediaMetadata.subtitle.toString()).isEqualTo("Ann Author")
    }

    @Test
    fun `a book of Home stands under its section's title, in English`() {
        assertThat(CarSection.entries.map { book(section = it).groupTitle() })
            .containsExactly("Continue", "Up next", "Recently added").inOrder()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a book of Home stands under its section's title, in French`() {
        assertThat(CarSection.entries.map { book(section = it).groupTitle() })
            .containsExactly("Reprendre", "À suivre", "Ajoutés récemment").inOrder()
    }

    // --- a series

    @Test
    fun `a series is a folder of audiobooks to browse, with its name and how many books it has`() {
        val series = items.of(CarNode.Series(SeriesId("4"), "A Saga", 2))

        assertThat(series.mediaId).isEqualTo("series/4")
        assertThat(series.mediaMetadata.isBrowsable).isTrue()
        assertThat(series.mediaMetadata.isPlayable).isFalse()
        assertThat(series.mediaMetadata.mediaType).isEqualTo(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
        assertThat(series.mediaMetadata.title.toString()).isEqualTo("A Saga")
        assertThat(series.mediaMetadata.subtitle.toString()).isEqualTo("2 books")
        assertThat(items.of(CarNode.Series(SeriesId("4"), "A Saga", 1)).mediaMetadata.subtitle.toString())
            .isEqualTo("1 book")
        series.asksGridOfBooksAndListOfFolders()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a series says how many books it has in French`() {
        assertThat(items.of(CarNode.Series(SeriesId("4"), "A Saga", 2)).mediaMetadata.subtitle.toString())
            .isEqualTo("2 livres")
        assertThat(items.of(CarNode.Series(SeriesId("4"), "A Saga", 1)).mediaMetadata.subtitle.toString())
            .isEqualTo("1 livre")
    }

    // --- the covers' addresses

    @Test
    fun `a cover's address names its profile and its book, whatever their ids hold`() {
        val uri = CarCovers.uri(Authority, "profile 1", BookId("a/b?c"))

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(Authority)
        assertThat(uri.pathSegments).containsExactly("profile 1", "a/b?c").inOrder()
        assertThat(CarCovers.authority("app.tfdn.lirelle")).isEqualTo(Authority)
    }
}
