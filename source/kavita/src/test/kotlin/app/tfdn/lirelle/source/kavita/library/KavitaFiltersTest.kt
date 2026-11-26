package app.tfdn.lirelle.source.kavita.library

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class KavitaFiltersTest {

    @Test
    fun `a library and a name narrow the series together`() {
        val filter = seriesFilter(
            listOf(
                Statement(SeriesField.Libraries, Comparison.Equal, "1"),
                Statement(SeriesField.Name, Comparison.Matches, "ast"),
            ),
        )

        assertThat(filter).isEqualTo(
            json(
                """{"statements":[{"comparison":0,"field":19,"value":"1"},{"comparison":7,"field":1,"value":"ast"}],
                "combination":1,"sortOptions":{"sortField":1,"isAscending":true},"limitTo":0}""",
            ),
        )
    }

    @Test
    fun `an author's series are those they wrote or drew`() {
        val filter = seriesFilter(
            listOf(
                Statement(SeriesField.Writers, Comparison.Contains, "43"),
                Statement(SeriesField.Penciller, Comparison.Contains, "43"),
            ),
            or = true,
            sortField = SeriesSortField.LastChapterAdded,
            ascending = false,
        )

        assertThat(filter).isEqualTo(
            json(
                """{"statements":[{"comparison":5,"field":17,"value":"43"},{"comparison":5,"field":16,"value":"43"}],
                "combination":0,"sortOptions":{"sortField":4,"isAscending":false},"limitTo":0}""",
            ),
        )
    }

    @Test
    fun `the authors are the writers and pencillers, narrowed by name`() {
        val filter = personFilter(
            listOf(
                Statement(PersonField.Role, Comparison.Contains, WriterAndPenciller),
                Statement(PersonField.Name, Comparison.Matches, "gosc"),
            ),
            sortField = PersonSortField.ChapterCount,
        )

        assertThat(filter).isEqualTo(
            json(
                """{"statements":[{"comparison":5,"field":1,"value":"3,4"},{"comparison":7,"field":2,"value":"gosc"}],
                "combination":1,"sortOptions":{"sortField":3,"isAscending":true},"limitTo":0}""",
            ),
        )
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
