package app.tfdn.lirelle.feature.player.car

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.service.LastBook
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the player is set to for a tap or a word of the car. */
@RunWith(AndroidJUnit4::class)
class CarAsksTest {

    private val shelves = FakeCarShelves()
    private val car = FakeCarLink()
    private var lastBook: LastBook? = null
    private val listed = mutableMapOf<String, CarNode.Book>()

    private var closed = false

    private fun asks() = CarAsks(
        shelves,
        CarTexts(ApplicationProvider.getApplicationContext<Context>().resources),
        CoroutineScope(Dispatchers.Unconfined),
        { listed[it] },
        { closed },
    ) { lastBook }

    private fun tapped(mediaId: String) = listOf(MediaItem.Builder().setMediaId(mediaId).build())

    private fun said(query: String?) = listOf(
        MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
            .build(),
    )

    private fun book(id: String, title: String, section: CarSection? = null) =
        CarNode.Book(BookId(id), title, null, section)

    private fun CarAsks.answer(items: List<MediaItem>) = runCatching { items(car, items).get() }

    @Test
    fun `a book tapped is set as that book starting, under the title it was listed with`() {
        listed["book/7"] = book("7", "The Long Road")

        val items = asks().answer(tapped("book/7")).getOrThrow()

        assertThat(items.map { it.mediaId }).containsExactly("start/7")
        assertThat(items.single().mediaMetadata.title.toString()).isEqualTo("The Long Road")
        assertThat(asks().answer(tapped("book/8")).getOrThrow().map { it.mediaId }).containsExactly("start/8")
        assertThat(shelves.searches).isEmpty()
    }

    @Test
    fun `words are searched among what can be listened to, and the first book found starts`() {
        shelves.searched = CarAnswer.Nodes(listOf(book("3", "A Road"), book("4", "Roads")))

        val items = asks().answer(said("road")).getOrThrow()

        assertThat(items.map { it.mediaId }).containsExactly("start/3")
        assertThat(items.single().mediaMetadata.title.toString()).isEqualTo("A Road")
        assertThat(shelves.searches).containsExactly("road")
    }

    @Test
    fun `words that find nothing start nothing, and the car is told so`() {
        val answer = asks().answer(said("road"))

        assertThat(answer.isFailure).isTrue()
        assertThat(car.said.map { it.message }).containsExactly("Nothing to listen to for “road”.")
    }

    @Test
    fun `a search that cannot be run says why, as a list does`() {
        shelves.searched = CarAnswer.Notice(CarNotice.Unreachable)

        assertThat(asks().answer(said("road")).isFailure).isTrue()
        assertThat(car.said.map { it.message }).containsExactly("The server cannot be reached.")
    }

    @Test
    fun `nothing named resumes the last book, as a play button does`() {
        lastBook = LastBook(BookId("42"), "The Long Road", null)

        assertThat(asks().answer(said(null)).getOrThrow().map { it.mediaId }).containsExactly("resume/42")
        assertThat(asks().answer(said("  ")).getOrThrow().map { it.mediaId }).containsExactly("resume/42")
        assertThat(asks().answer(emptyList()).getOrThrow().map { it.mediaId }).containsExactly("resume/42")
        assertThat(shelves.searches).isEmpty()
    }

    @Test
    fun `without a last book, the first book to continue starts`() {
        shelves.places[CarPlace.Tab.Home] = CarAnswer.Nodes(
            listOf(book("1", "Next", CarSection.UpNext), book("2", "Begun", CarSection.Continue)),
        )

        assertThat(asks().answer(said(null)).getOrThrow().map { it.mediaId }).containsExactly("start/2")
    }

    @Test
    fun `with nothing to resume, nothing starts and the car is told so`() {
        shelves.places[CarPlace.Tab.Home] = CarAnswer.Nodes(listOf(book("1", "Next", CarSection.UpNext)))

        assertThat(asks().answer(said(null)).isFailure).isTrue()
        assertThat(car.said.map { it.message }).containsExactly("No book to resume.")
    }

    @Test
    fun `a book that could not be loaded is said to the car that asked for it, in the phone's words`() {
        val asks = asks()
        asks.failed()
        assertThat(car.said).isEmpty()

        asks.answer(tapped("book/7"))
        asks.failed()

        assertThat(car.said.map { it.message }).containsExactly("This book could not be loaded")
    }

    @Test
    fun `once the profile closed nothing of it starts, and nothing of it is asked`() {
        lastBook = LastBook(BookId("42"), "The Long Road", null)
        closed = true

        assertThat(asks().answer(tapped("book/7")).isFailure).isTrue()
        assertThat(asks().answer(said("road")).isFailure).isTrue()
        assertThat(asks().answer(said(null)).isFailure).isTrue()
        assertThat(shelves.searches).isEmpty()
        assertThat(shelves.asked).isEmpty()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French`() {
        asks().answer(said("route"))
        asks().answer(said(null))

        assertThat(car.said.map { it.message })
            .containsExactly("Rien à écouter pour « route ».", "Aucun livre à reprendre.")
            .inOrder()
    }
}
