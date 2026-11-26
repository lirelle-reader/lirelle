package app.tfdn.lirelle

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * What only a source speaks: a server's routes, and Retrofit, through which only the sources' APIs are spoken.
 * Every source may speak them, in its own module.
 */
private val ServerSpeech = listOf(
    // A route of a server's API, from the server's root or relative to it: BookOrbit's and Komga's live under these.
    Regex("""\bapi/v[12]\b"""),
    // Retrofit, imported or named in full.
    Regex("""\bretrofit2\."""),
)

/**
 * A source of one kind of server: where it lives, from the project's root, how a build depends on it, and its own
 * package, whose types only it may name.
 */
private class SourceModule(val folder: String, val dependency: String, val packageName: String, val fewest: Int) {
    val ownSpeech = Regex("""\b${Regex.escape(packageName)}\b""")
}

private val Sources = listOf(
    SourceModule("source/bookorbit/", "\":source:bookorbit\"", "app.tfdn.lirelle.source.bookorbit", fewest = 30),
    SourceModule("source/komga/", "\":source:komga\"", "app.tfdn.lirelle.source.komga", fewest = 5),
    SourceModule("source/kavita/", "\":source:kavita\"", "app.tfdn.lirelle.source.kavita", fewest = 5),
)

/** The one module that may depend on a source, by its Gradle path: the app, which ships them. */
private const val TheApp = "app/build.gradle.kts"

/** Where the modules keep what is shipped. */
private val ModuleGroups = listOf("app", "core", "feature", "source")

/** The fewest files the walk must find to have looked at the project at all. */
private const val FewestSources = 200

/** The lines of [source] that speak as a source does, with their numbers: [own] is a source's package. */
internal fun sourceLines(source: String, own: List<Regex> = Sources.map { it.ownSpeech }): List<String> =
    source.lines().withIndex()
        .filter { (_, line) -> (ServerSpeech + own).any { it.containsMatchIn(line) } }
        .map { (index, line) -> "${index + 1}: ${line.trim()}" }

/** The project's root: a unit test runs in its module's folder, somewhere under it. */
private fun projectRoot(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

/** The sources that are shipped, of every module, by their path from [root]; what a build left is not looked at. */
private fun mainSources(root: File): Map<String, File> = ModuleGroups.flatMap { group ->
    File(root, group).walkTopDown().onEnter { it.name != "build" }
        .filter { it.isFile && it.extension in setOf("kt", "java") && "/src/main/" in it.invariantSeparatorsPath }
        .toList()
}.associateBy { it.relativeTo(root).invariantSeparatorsPath }

/** The build of every module, by its path from [root]. */
private fun moduleBuilds(root: File): Map<String, File> = ModuleGroups.flatMap { group ->
    File(root, group).walkTopDown().onEnter { it.name != "build" && it.name != "src" }
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .toList()
}.associateBy { it.relativeTo(root).invariantSeparatorsPath }

/**
 * Each kind of server is spoken in its source alone (`source/<kind>`): no module outside the sources names a route,
 * uses Retrofit or names a type of a source, and no source names another's types, so that a kind of server adds a
 * source beside the others and changes nothing elsewhere. The app chooses the source by the profile's kind without
 * naming it (`SourceChoice.kt`), and only the app depends on the sources.
 *
 * It is a scan of text, no more: it sees the routes as written, Retrofit and the sources' packages, not a server's
 * rule written in other words; those are for the review.
 */
class EachSourceStaysInItsSourceTest {

    private val root = projectRoot()
    private val sources = mainSources(root)

    private fun inSource(path: String) = Sources.any { path.startsWith(it.folder) }

    @Test
    fun `no file outside the sources speaks as a source does`() {
        assertWithMessage("sources found").that(sources.size).isAtLeast(FewestSources)
        val speaking = sources.filterKeys { !inSource(it) }
            .mapValues { (_, file) -> sourceLines(file.readText()) }
            .filterValues { it.isNotEmpty() }

        assertWithMessage("files outside the sources that speak as a source").that(speaking).isEmpty()
    }

    @Test
    fun `no source names the types of another`() {
        val speaking = Sources.associate { module ->
            val others = Sources.filter { it !== module }.map { it.ownSpeech }
            module.folder to sources.filterKeys { it.startsWith(module.folder) }
                .mapValues { (_, file) ->
                    file.readText().lines().filter { line -> others.any { it.containsMatchIn(line) } }
                }
                .filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }

        assertWithMessage("sources that name another source's types").that(speaking).isEmpty()
    }

    /** Without this, finding nothing could be looking at nothing, or with ways that no longer match the sources. */
    @Test
    fun `each source is among those read, and speaks as a source`() {
        val speaking = Sources.associate { module ->
            module.folder to sources.filterKeys { it.startsWith(module.folder) }
                .count { (_, file) -> sourceLines(file.readText(), listOf(module.ownSpeech)).isNotEmpty() }
        }

        Sources.forEach { assertWithMessage(it.folder).that(speaking[it.folder]).isAtLeast(it.fewest) }
    }

    @Test
    fun `only the app depends on each source`() {
        val builds = moduleBuilds(root)
        assertWithMessage("module builds found").that(builds.keys).contains(TheApp)

        Sources.forEach { module ->
            val depending = builds.filterValues { module.dependency in it.readText() }.keys
            assertWithMessage(module.folder).that(depending).containsExactly(TheApp)
        }
    }

    @Test
    fun `the routes, Retrofit and each source's types are caught`() {
        val source = """
            private const val Base = "https://books.example/api/v1/"
            @GET("/api/v1/books/{id}")
            @GET("api/v2/users/me")
            import retrofit2.HttpException
            import retrofit2.http.GET
            val refused = (failure as? retrofit2.HttpException)?.code()
            import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
            import app.tfdn.lirelle.source.bookorbit.di.*
            val errors = app.tfdn.lirelle.source.bookorbit.network.ErrorMapper(json)
            import app.tfdn.lirelle.source.komga.network.KomgaProbe
        """.trimIndent()

        assertThat(sourceLines(source)).hasSize(source.lines().size)
        assertThat(sourceLines("val a = 1\nimport retrofit2.Call")).containsExactly("2: import retrofit2.Call")
    }

    @Test
    fun `the neutral contracts, the kinds of server and other routes are left alone`() {
        val source = """
            import app.tfdn.lirelle.core.session.ServerKind
            import app.tfdn.lirelle.core.session.profile.SourceOf
            val kind = ServerKind.BookOrbit
            @SourceOf(ServerKind.Komga) komga: Provider<LibraryRepository>
            import app.tfdn.lirelle.core.network.ApiError
            val file = "${'$'}server/files/${'$'}fileId"
            val other = "${'$'}server/api/v10/books"
            val komgaLike = KomgaLikeCapabilities
            // Retrofit is the sources'.
        """.trimIndent()

        assertThat(sourceLines(source)).isEmpty()
    }
}
