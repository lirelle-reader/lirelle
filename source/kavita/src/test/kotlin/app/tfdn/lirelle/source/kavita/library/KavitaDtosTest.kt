package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.source.kavita.library.dto.BrowsePersonDto
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.GenreDto
import app.tfdn.lirelle.source.kavita.library.dto.LibraryDto
import app.tfdn.lirelle.source.kavita.library.dto.PersonDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListDto
import app.tfdn.lirelle.source.kavita.library.dto.ReadingListItemDto
import app.tfdn.lirelle.source.kavita.library.dto.RecentlyUpdatedDto
import app.tfdn.lirelle.source.kavita.library.dto.SearchResultDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDetailDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesMetadataDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.RecordedOn
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import app.tfdn.lirelle.source.kavita.testing.fixturePagination
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Headers.Companion.headersOf
import org.junit.Test
import retrofit2.Response

class KavitaDtosTest {

    @Test
    fun `every recorded answer is read, on both versions`() {
        val read = mutableListOf<String>()
        for (version in RecordedOn.entries) {
            for ((name, serializer) in Readers) {
                if (!recorded(name, version)) continue
                KavitaJson.decodeFromString(serializer, fixtureBody(name, version))
                read += "${version.folder}/$name"
            }
        }
        // Every name is recorded on the latest version; most on the oldest too.
        assertThat(read.count { it.startsWith(RecordedOn.Latest.folder) }).isEqualTo(Readers.size)
        assertThat(read.count { it.startsWith(RecordedOn.Minimum.folder) }).isGreaterThan(Readers.size / 2)
    }

    @Test
    fun `a series detail keeps its volumes and the chapters outside them`() {
        for (version in RecordedOn.entries) {
            val detail = KavitaJson.decodeFromString<SeriesDetailDto>(fixtureBody("detail-asterix", version))

            assertThat(detail.volumes).hasSize(1)
            assertThat(detail.volumes.single().chapters).hasSize(3)
            assertThat(detail.storylineChapters).hasSize(3)
            assertThat(detail.specials).isEmpty()
        }
    }

    @Test
    fun `a group of recently updated series names its series`() {
        for (version in RecordedOn.entries) {
            val groups = KavitaJson.decodeFromString<List<RecentlyUpdatedDto>>(fixtureBody("recent-updated", version))

            assertThat(groups).isNotEmpty()
            assertThat(groups.all { it.seriesId > 0 }).isTrue()
        }
    }

    @Test
    fun `a chapter reads its people, its files and its dates`() {
        val chapter = KavitaJson.decodeFromString<ChapterDto>(fixtureBody("chapter-asterix"))

        assertThat(chapter.id).isEqualTo(44)
        assertThat(chapter.volumeId).isEqualTo(7)
        assertThat(chapter.writers.map { it.name }).containsExactly("Albert Uderzo", "René Goscinny").inOrder()
        assertThat(chapter.pencillers.map { it.name }).containsExactly("Albert Uderzo")
        assertThat(chapter.publishers.map { it.name }).containsExactly("Dargaud")
        assertThat(chapter.files.single().format).isEqualTo(1)
        assertThat(chapter.releaseDate).isEqualTo("1961-07-31T00:00:00")
    }

    @Test
    fun `the total of a list comes from its Pagination header`() {
        val header = fixturePagination("ondeck20")!!
        val response = Response.success(emptyList<SeriesDto>(), headersOf("Pagination", header))

        assertThat(response.totalItems()).isEqualTo(KavitaJson.decodeFromString<Total>(header).totalItems)
    }

    @Test
    fun `a list without a readable Pagination header has no total`() {
        assertThat(Response.success(emptyList<SeriesDto>()).totalItems()).isNull()
        assertThat(Response.success(emptyList<SeriesDto>(), headersOf("Pagination", "nope")).totalItems()).isNull()
        assertThat(Response.success(emptyList<SeriesDto>(), headersOf("Pagination", "[1]")).totalItems()).isNull()
    }

    @kotlinx.serialization.Serializable
    private data class Total(val totalItems: Int)

    private companion object {
        val SeriesList = ListSerializer(SeriesDto.serializer())
        val Persons = ListSerializer(BrowsePersonDto.serializer())

        /** Each recorded answer the library reads, with what it is read into. */
        val Readers: Map<String, KSerializer<*>> = buildMap {
            put("libraries", ListSerializer(LibraryDto.serializer()))
            listOf("all-lib-name", "all-first", "all-genre", "ondeck", "ondeck20", "wanttoread", "series-by-person")
                .forEach { put(it, SeriesList) }
            listOf("series-dune", "series-asterix", "series-lone", "series-for-chapter", "series-harry-potter")
                .forEach { put(it, SeriesDto.serializer()) }
            listOf("asterix", "one-piece", "dune", "harry-potter", "lone", "luckyluke")
                .forEach { put("detail-$it", SeriesDetailDto.serializer()) }
            listOf("asterix", "dune", "harry-potter", "one-piece")
                .forEach { put("metadata-$it", SeriesMetadataDto.serializer()) }
            listOf("asterix", "dune", "harry-potter").forEach { put("volume-$it", VolumeDto.serializer()) }
            listOf("chapter-asterix", "chapter-lone", "chapter-one-piece", "continue-asterix", "continue-dune")
                .forEach { put(it, ChapterDto.serializer()) }
            listOf("continue-naruto", "continue-potter", "continue-harry-potter", "continue-one-piece")
                .forEach { put(it, ChapterDto.serializer()) }
            put("genres", ListSerializer(GenreDto.serializer()))
            listOf("persons", "persons-name", "person-count", "persons-christie", "persons-gosc")
                .forEach { put(it, Persons) }
            put("person-by-name", PersonDto.serializer())
            listOf("asterix", "dune", "christie", "piece").forEach { put("search-$it", SearchResultDto.serializer()) }
            put("recent-updated", ListSerializer(RecentlyUpdatedDto.serializer()))
            put("readinglists", ListSerializer(ReadingListDto.serializer()))
            put("readinglist-items", ListSerializer(ReadingListItemDto.serializer()))
        }

        fun recorded(name: String, version: RecordedOn): Boolean =
            RecordedOn::class.java.getResource("/kavita/${version.folder}/$name.json") != null
    }
}
