package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorDetailDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorsDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsCollectionDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsCollectionsPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsFilterDataAnswerDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsInProgressDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemsPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibrariesDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMeDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsPlaylistsDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSearchDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSeriesDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSeriesPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsShelfDto
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

/** Each recorded answer, by its name, and how it is read. */
private val Readers: Map<String, KSerializer<*>> = mapOf(
    "libraries" to AbsLibrariesDto.serializer(),
    "items-livres-added" to AbsItemsPageDto.serializer(),
    "items-livres-all" to AbsItemsPageDto.serializer(),
    "items-bd-added" to AbsItemsPageDto.serializer(),
    "items-series-h2g2" to AbsItemsPageDto.serializer(),
    "items-genre-humour" to AbsItemsPageDto.serializer(),
    "item-h2g2-expanded" to AbsItemDto.serializer(),
    "item-petit-prince-expanded" to AbsItemDto.serializer(),
    "item-asterix-expanded" to AbsItemDto.serializer(),
    "me" to AbsMeDto.serializer(),
    "items-in-progress" to AbsInProgressDto.serializer(),
    "personalized-livres" to ListSerializer(AbsShelfDto.serializer()),
    "personalized-bd" to ListSerializer(AbsShelfDto.serializer()),
    "series-livres" to AbsSeriesPageDto.serializer(),
    "series-h2g2" to AbsSeriesDto.serializer(),
    "authors-livres" to AbsAuthorsDto.serializer(),
    "author-adams" to AbsAuthorDetailDto.serializer(),
    "collections-livres" to AbsCollectionsPageDto.serializer(),
    "collection-classiques" to AbsCollectionDto.serializer(),
    "playlists" to AbsPlaylistsDto.serializer(),
    "filterdata-livres" to AbsFilterDataAnswerDto.serializer(),
    "search-asterix" to AbsSearchDto.serializer(),
    "search-potter" to AbsSearchDto.serializer(),
    "search-herbert" to AbsSearchDto.serializer(),
)

private inline fun <reified T> read(name: String, on: RecordedOn): T = AbsJson.decodeFromString(fixtureBody(name, on))

/** Every answer recorded on 2.38.0 and 2.26.3 is read, in the shapes each version gives. */
class AbsDtosTest {

    @Test
    fun `every recorded answer is read, on both versions`() {
        RecordedOn.entries.forEach { on ->
            Readers.forEach { (name, serializer) ->
                val read = runCatching { AbsJson.decodeFromString(serializer, fixtureBody(name, on)) }

                assertWithMessage("${on.folder}/$name: ${read.exceptionOrNull()}").that(read.isSuccess).isTrue()
            }
        }
    }

    @Test
    fun `an item of a series' list names that series alone, as an object`() {
        RecordedOn.entries.forEach { on ->
            val items = read<AbsItemsPageDto>("items-series-h2g2", on)

            assertWithMessage(on.name).that(items.total).isEqualTo(7)
            items.results.forEach {
                assertWithMessage(on.name).that(it.media.metadata.series).hasSize(1)
                assertWithMessage(on.name).that(it.media.metadata.series.single().name)
                    .isEqualTo("Hitchhiker's Guide to the Galaxy")
            }
        }
    }

    @Test
    fun `the lists keep their totals and their books`() {
        RecordedOn.entries.forEach { on ->
            assertWithMessage(on.name).that(read<AbsItemsPageDto>("items-livres-all", on).results).hasSize(40)
            val series = read<AbsSeriesPageDto>("series-livres", on)
            assertWithMessage(on.name).that(series.total).isEqualTo(65)
            assertWithMessage(on.name).that(series.results.first().books).isNotEmpty()
            assertWithMessage(on.name).that(read<AbsAuthorsDto>("authors-livres", on).authors.first().lastFirst)
                .isEqualTo("Verne, Jules")
            val collection = read<AbsCollectionsPageDto>("collections-livres", on).results.single()
            assertWithMessage(on.name).that(collection.name).isEqualTo("Classiques")
            assertWithMessage(on.name).that(collection.books).isNotEmpty()
        }
    }

    @Test
    fun `an author's answer holds the author's items and series`() {
        RecordedOn.entries.forEach { on ->
            val author = read<AbsAuthorDetailDto>("author-adams", on)

            assertWithMessage(on.name).that(author.name).isEqualTo("Douglas Adams")
            assertWithMessage(on.name).that(author.libraryItems).isNotEmpty()
            assertWithMessage(on.name).that(author.series.map { it.name }).contains("H2G2")
            assertWithMessage(on.name).that(author.series.first().items).isNotEmpty()
        }
    }

    @Test
    fun `a playlist's items name their library items`() {
        RecordedOn.entries.forEach { on ->
            val playlist = read<AbsPlaylistsDto>("playlists", on).playlists.first()

            assertWithMessage(on.name).that(playlist.name).isEqualTo("À écouter en voiture")
            playlist.items.forEach {
                assertWithMessage(on.name).that(it.libraryItem?.id).isEqualTo(it.libraryItemId)
            }
        }
    }

    @Test
    fun `a search finds books, series and authors, and the genres come with the filter data`() {
        RecordedOn.entries.forEach { on ->
            val asterix = read<AbsSearchDto>("search-asterix", on)
            val herbert = read<AbsSearchDto>("search-herbert", on)
            val genres = read<AbsFilterDataAnswerDto>("filterdata-livres", on).filterdata.genres

            assertWithMessage(on.name).that(asterix.book.single().libraryItem.media.metadata.title)
                .isEqualTo("Astérix 1")
            assertWithMessage(on.name).that(asterix.series.single().series.name).isEqualTo("Astérix")
            assertWithMessage(on.name).that(herbert.authors.single().name).isEqualTo("Frank Herbert")
            assertWithMessage(on.name).that(genres).contains("Humour")
        }
    }

    @Test
    fun `Home's shelves come with their kind`() {
        RecordedOn.entries.forEach { on ->
            val shelves = read<List<AbsShelfDto>>("personalized-livres", on)
            val inProgress = read<AbsInProgressDto>("items-in-progress", on)

            assertWithMessage(on.name).that(shelves.map { it.id })
                .containsAtLeast("continue-listening", "recently-added").inOrder()
            assertWithMessage(on.name).that(inProgress.libraryItems).isNotEmpty()
        }
    }
}
