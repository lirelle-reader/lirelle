package app.tfdn.lirelle

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The ways to read an id as a number, or to make one from a number: what only the BookOrbit side, the one server
 * whose ids are numbers, and the Kavita side's id conversions may do. Anywhere else, an id is text that means
 * nothing but itself; so is the version of a listening state, which the app compares for equality only
 * (BookOrbit's revisions are numbers).
 */
private val NumericUses = listOf(
    // An id's text read as a number.
    Regex("""\.value\.to(Long|Int)(OrNull)?\b"""),
    // An id read as the Kavita number it stands for (KavitaIds.kt).
    Regex("""\.value\??\.kavitaNumber\("""),
    // An id turned into the BookOrbit number it stands for (BookOrbitNumbers.kt).
    Regex("""\.bookOrbit\(\)"""),
    // An id made from a BookOrbit number (BookOrbitIds.kt).
    Regex("""\b(book|file|series|author|shelf|mark|read|library)IdOf\("""),
    // An id made from a number literal, quoted or not, or from a number's text: a type's name, not a call like
    // setMediaId.
    Regex("""\b[A-Z]\w*Id\(\s*-?\d"""),
    Regex("""\b[A-Z]\w*Id\(\s*"-?\d+""""),
    Regex("""\b[A-Z]\w*Id\(.*\.toString\(\)\s*\)"""),
    Regex("""\b[A-Z]\w*Id\("\$"""),
    // A listening state's version, as opaque as an id: made from a BookOrbit revision or turned back into one
    // (BookOrbitIds.kt, BookOrbitNumbers.kt), or read as a number.
    Regex("""\b(version|revision)Of\("""),
    Regex("""[vV]ersion\??\.to(Long|Int)(OrNull)?\b"""),
)

/**
 * The files that may convert, by their path from the project's root, and why each may. Each one is a part of the
 * BookOrbit side, which 2b moves behind the source, one of the Kavita side's id conversions (Kavita numbers its
 * series, libraries and lists, and Lirelle writes its books `v12` or `c34`), or a test helper that stands in for a
 * BookOrbit server or its books; nothing else may be added without a reason as good.
 */
private val Allowed: Map<String, String> = mapOf(
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/network/BookOrbitIds.kt" to
        "makes the ids, and the listening states' versions, from BookOrbit's numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/network/BookOrbitNumbers.kt" to
        "turns the ids, and the listening states' versions, back into BookOrbit's numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/Mappers.kt" to
        "maps BookOrbit's answers, numbered, to the library's models",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/BookOrbitFiles.kt" to
        "maps BookOrbit's files, numbered, to the library's book files",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/Editions.kt" to
        "maps BookOrbit's linked books, numbered, to the editions of a book",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/history/HistoryMapper.kt" to
        "maps BookOrbit's history answers, numbered, to the history's models",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/history/BookOrbitHistoryRepository.kt" to
        "asks BookOrbit for a finished reading's marks and rating by its number",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultLibraryRepository.kt" to
        "asks BookOrbit for books, series, authors and shelves by their numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultReaderRepository.kt" to
        "asks BookOrbit for positions, sessions, readings and next volumes by their numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultMarksRepository.kt" to
        "asks BookOrbit for bookmarks and highlights by their numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultAudioRepository.kt" to
        "asks BookOrbit for an audiobook's manifest, state and assets by its number, and writes on its revisions",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultAudioBookmarkRepository.kt" to
        "asks BookOrbit for an audiobook's bookmarks by its number",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/library/DefaultReadAlongRepository.kt" to
        "asks BookOrbit for a book's read-along by its numbers",
    "source/bookorbit/src/main/kotlin/app/tfdn/lirelle/source/bookorbit/comics/BookOrbitPageSource.kt" to
        "asks BookOrbit for an album's pages, and writes their addresses, by its file's number",
    "source/kavita/src/main/kotlin/app/tfdn/lirelle/source/kavita/library/KavitaIds.kt" to
        "makes the ids of series, libraries and shelves from Kavita's numbers, and turns them back",
    "source/kavita/src/main/kotlin/app/tfdn/lirelle/source/kavita/library/KavitaBooks.kt" to
        "makes a book's id, `v12` or `c34`, from the number of its Kavita volume or chapter, and reads it back",
    "core/testing/src/main/kotlin/app/tfdn/lirelle/core/testing/FakeMarksRepository.kt" to
        "plays a BookOrbit server in the tests: counts the ids of the marks it makes, as BookOrbit does",
    "core/testing/src/main/kotlin/app/tfdn/lirelle/core/testing/LibrarySamples.kt" to
        "the tests' sample books, as a BookOrbit server numbers them",
)

/** Where the modules keep what is shipped. */
private val ModuleGroups = listOf("app", "core", "feature", "source")

/** The source of each kind of server, which the walk must read: Komga's and Audiobookshelf's ids are no numbers. */
private val SourceFolders = listOf("source/bookorbit/", "source/komga/", "source/kavita/", "source/audiobookshelf/")

/** The fewest files the walk must find to have looked at the project at all. */
private const val FewestSources = 200

/** The lines of [source] that read an id as a number or make one from a number, with their numbers. */
internal fun numericIdUses(source: String): List<String> = source.lines().withIndex()
    .filter { (_, line) -> NumericUses.any { it.containsMatchIn(line) } }
    .map { (index, line) -> "${index + 1}: ${line.trim()}" }

/** The project's root: a unit test runs in its module's folder, somewhere under it. */
private fun projectRoot(): File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

/** The Kotlin sources that are shipped, of every module; what a build left is not looked at. */
private fun mainSources(root: File): List<File> = ModuleGroups.flatMap { group ->
    File(root, group).walkTopDown().onEnter { it.name != "build" }
        .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
}

/**
 * An id is opaque outside the BookOrbit side and the Kavita side's id conversions: no source reads it as a number,
 * compares it as one or makes one from a number, so that a server whose ids are not numbers (Komga's) breaks
 * nothing there.
 *
 * It is a scan of text, no more: it sees the usual ways (the BookOrbit conversions, `.value.toLong()`, an id
 * made from a number or its text), not a number reached by a longer way round; those are for the review.
 */
class OpaqueIdsTest {

    private val root = projectRoot()
    private val sources = mainSources(root)

    @Test
    fun `no source outside the BookOrbit side and Kavita's conversions reads an id as a number or makes one`() {
        assertWithMessage("sources found").that(sources.size).isAtLeast(FewestSources)
        val uses = sources.associate { it.relativeTo(root).invariantSeparatorsPath to numericIdUses(it.readText()) }
            .filter { (path, lines) -> lines.isNotEmpty() && path !in Allowed }

        assertWithMessage("sources that use ids as numbers outside the BookOrbit side and Kavita's conversions")
            .that(uses).isEmpty()
    }

    @Test
    fun `the numeric ways are caught`() {
        val source = """
            val number = book.id.value.toLong()
            val index = file.id.value.toInt()
            val maybe = book.id.value.toLongOrNull()
            val perhaps = file.id.value.toIntOrNull()
            val kavita = shelf.id.value.kavitaNumber()
            val maybeKavita = source.id?.value?.kavitaNumber()
            val asked = api.book(bookId.bookOrbit())
            val made = bookIdOf(dto.id)
            val listed = libraryIdOf(dto.id)
            val literal = BookId(42)
            val quoted = BookId("-1")
            val numbered = FileId("12")
            val text = FileId(dto.id.toString())
            val template = SeriesId("${'$'}number")
            val version = versionOf(dto.revision)
            val revision = revisionOf(write.baseVersion)
            val counted = state.version.toInt()
            val maybeCounted = write.baseVersion?.toIntOrNull()
        """.trimIndent()

        assertThat(numericIdUses(source)).hasSize(source.lines().size)
    }

    @Test
    fun `ids handled as text are left alone`() {
        val source = """
            val id = BookId(entry.toRoute<BookDestination>().id)
            val sample = FileId("0f3a")
            val mark = MarkId("b${'$'}{serverId.value}")
            val media = "resume/${'$'}{book.bookId.value}"
            val item = MediaItem.Builder().setMediaId("${'$'}{book.bookId}/${'$'}index")
            val same = first.id == second.id
            val known = server.version == store.knownVersion(bookId)
            val content = manifest.contentVersion
        """.trimIndent()

        assertThat(numericIdUses(source)).isEmpty()
    }

    /** Without this, a source the walk misses would pass for one that keeps its ids opaque. */
    @Test
    fun `every source of a kind of server is read`() {
        val read = sources.map { it.relativeTo(root).invariantSeparatorsPath }

        SourceFolders.forEach { folder -> assertWithMessage(folder).that(read.any { it.startsWith(folder) }).isTrue() }
    }

    /** An entry kept for a file that is gone, or that no longer converts, would hide the next one. */
    @Test
    fun `every file allowed still needs to be`() {
        val needless = Allowed.keys.filter { path ->
            File(root, path).takeIf { it.isFile }?.let { numericIdUses(it.readText()) }.isNullOrEmpty()
        }

        assertThat(needless).isEmpty()
    }
}
