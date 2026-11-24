package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The app's sorts and filters, as BookOrbit's book query says them. */
class BookQueryBuilderTest {

    private fun json(text: String): JsonObject = ApiJson.decodeFromString(text)

    private fun build(request: BookListRequest, page: Int = 0, size: Int = 60) = buildBookQuery(request, page, size)

    @Test
    fun `default request sorts by date added, newest first, and folds linked copies`() {
        assertThat(build(BookListRequest())).isEqualTo(
            json(
                """{"sort":[{"field":"addedAt","dir":"desc"}],
                    "pagination":{"page":0,"size":60},"collapseLinked":true}""",
            ),
        )
    }

    @Test
    fun `page and size are passed through`() {
        val query = build(BookListRequest(), page = 3, size = 25)

        assertThat(query["pagination"]).isEqualTo(json("""{"page":3,"size":25}"""))
    }

    @Test
    fun `each sort maps to its server field and direction`() {
        fun sortOf(sort: BookSort, direction: SortDirection) =
            build(BookListRequest(sort = sort, direction = direction))["sort"].toString()

        assertThat(sortOf(BookSort.Title, SortDirection.Ascending)).isEqualTo("""[{"field":"title","dir":"asc"}]""")
        assertThat(sortOf(BookSort.Author, SortDirection.Descending)).isEqualTo("""[{"field":"author","dir":"desc"}]""")
        assertThat(sortOf(BookSort.Published, SortDirection.Descending))
            .isEqualTo("""[{"field":"publishedDate","dir":"desc"}]""")
    }

    @Test
    fun `sorting by series also orders the volumes`() {
        val sort = build(BookListRequest(sort = BookSort.Series, direction = SortDirection.Descending))["sort"]

        assertThat(sort.toString())
            .isEqualTo("""[{"field":"series","dir":"desc"},{"field":"seriesIndex","dir":"desc"}]""")
    }

    @Test
    fun `no filter key when nothing is filtered`() {
        assertThat(build(BookListRequest()).containsKey("filter")).isFalse()
    }

    @Test
    fun `format filter lists every format of the selected groups`() {
        val filters = BookFilters(formats = setOf(FormatFilter.Comics, FormatFilter.Pdf))

        assertThat(build(BookListRequest(filters = filters))["filter"]).isEqualTo(
            json(
                """{"type":"group","join":"AND","rules":[
                    {"type":"rule","field":"format","operator":"includesAny","value":["pdf","cbz","cbr","cb7"]}]}""",
            ),
        )
    }

    @Test
    fun `audio filter lists the audio formats`() {
        val rule = build(BookListRequest(filters = BookFilters(formats = setOf(FormatFilter.Audio))))["filter"]

        assertThat(rule.toString()).contains("""["m4b","mp3","m4a","opus","ogg","flac"]""")
    }

    @Test
    fun `status filter groups the server statuses`() {
        val filters = BookFilters(statuses = setOf(StatusFilter.Reading, StatusFilter.Read))

        assertThat(build(BookListRequest(filters = filters))["filter"]).isEqualTo(
            json(
                """{"type":"group","join":"AND","rules":[
                    {"type":"rule","field":"readStatus","operator":"includesAny",
                     "value":["reading","rereading","on_hold","read","skimmed"]}]}""",
            ),
        )
    }

    @Test
    fun `read-along is its own rule, combined with the others by AND`() {
        val filters = BookFilters(
            formats = setOf(FormatFilter.Epub),
            statuses = setOf(StatusFilter.Unread),
            readAlongOnly = true,
        )

        assertThat(build(BookListRequest(filters = filters))["filter"]).isEqualTo(
            json(
                """{"type":"group","join":"AND","rules":[
                    {"type":"rule","field":"format","operator":"includesAny","value":["epub","kepub"]},
                    {"type":"rule","field":"readStatus","operator":"includesAny","value":["unread"]},
                    {"type":"rule","field":"format","operator":"includesAny","value":["epub:readalong"]}]}""",
            ),
        )
    }

    @Test
    fun `every sort the user can choose has its BookOrbit fields`() {
        val fields = BookSort.entries.associateWith { sort ->
            build(BookListRequest(sort = sort)).getValue("sort").jsonArray.map { it.jsonObject.getValue("field") }
        }

        assertThat(fields).containsExactly(
            BookSort.Added, listOf(JsonPrimitive("addedAt")),
            BookSort.Title, listOf(JsonPrimitive("title")),
            BookSort.Author, listOf(JsonPrimitive("author")),
            BookSort.Series, listOf(JsonPrimitive("series"), JsonPrimitive("seriesIndex")),
            BookSort.Published, listOf(JsonPrimitive("publishedDate")),
        )
    }

    @Test
    fun `every format group the user can choose has its BookOrbit formats`() {
        val formats = FormatFilter.entries.associateWith { valuesOf(BookFilters(formats = setOf(it))) }

        assertThat(formats).containsExactly(
            FormatFilter.Epub, listOf("epub", "kepub"),
            FormatFilter.Pdf, listOf("pdf"),
            FormatFilter.Comics, listOf("cbz", "cbr", "cb7"),
            FormatFilter.Audio, listOf("m4b", "mp3", "m4a", "opus", "ogg", "flac"),
        )
    }

    @Test
    fun `every status group the user can choose has its BookOrbit statuses`() {
        val statuses = StatusFilter.entries.associateWith { valuesOf(BookFilters(statuses = setOf(it))) }

        assertThat(statuses).containsExactly(
            StatusFilter.Unread, listOf("unread"),
            StatusFilter.WantToRead, listOf("want_to_read"),
            StatusFilter.Reading, listOf("reading", "rereading", "on_hold"),
            StatusFilter.Read, listOf("read", "skimmed"),
            StatusFilter.Abandoned, listOf("abandoned"),
        )
    }

    @Test
    fun `the read-along filter is BookOrbit's read-along format`() {
        assertThat(valuesOf(BookFilters(readAlongOnly = true))).containsExactly("epub:readalong")
    }

    /** The values of the one rule [filters] make. */
    private fun valuesOf(filters: BookFilters): List<String> {
        val rules = build(BookListRequest(filters = filters)).getValue("filter").jsonObject.getValue("rules").jsonArray
        return rules.single().jsonObject.getValue("value").jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `a text is sent as q`() {
        val query = build(BookListRequest(text = "  mars "))

        assertThat(query["q"]).isEqualTo(JsonPrimitive("mars"))
    }

    @Test
    fun `no text sends no q`() {
        assertThat(build(BookListRequest(text = " "))).doesNotContainKey("q")
        assertThat(build(BookListRequest())).doesNotContainKey("q")
    }

    @Test
    fun `a genre is a rule next to the filters`() {
        val request = BookListRequest(genre = "Fantasy", filters = BookFilters(readAlongOnly = true))

        val rules = build(request).getValue("filter").jsonObject.getValue("rules").jsonArray

        assertThat(rules.map { it.jsonObject.getValue("field") }).containsExactly(
            JsonPrimitive("format"),
            JsonPrimitive("genre"),
        ).inOrder()
        assertThat(rules.last().jsonObject.getValue("value")).isEqualTo(JsonArray(listOf(JsonPrimitive("Fantasy"))))
    }
}
