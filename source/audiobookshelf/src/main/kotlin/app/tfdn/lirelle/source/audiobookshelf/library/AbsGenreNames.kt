package app.tfdn.lirelle.source.audiobookshelf.library

import java.util.concurrent.ConcurrentHashMap

/**
 * The genres' names as each book library writes them, by the name Lirelle shows: trimmed. Audiobookshelf keeps a
 * genre as it was typed, a stray space with it ("Humour "), and its genre filter asks for that name exactly: a
 * genre's books are asked of the server by its library's own name of it. Read with the genres ([read]), or when a
 * genre's books are asked of a library whose names are not known yet; kept for the profile.
 */
internal class AbsGenreNames {

    private val byLibrary = ConcurrentHashMap<String, Map<String, Set<String>>>()

    /** The library [libraryId]'s genres read now, as its filter data writes them, then kept. */
    suspend fun read(remote: AbsRemote, libraryId: String): List<String> =
        remote.api.filterData(libraryId).filterdata.genres.also { names ->
            byLibrary[libraryId] = names.groupBy { it.trim() }.filterKeys { it.isNotEmpty() }
                .mapValues { (_, written) -> written.toSet() }
        }

    /** The library [libraryId]'s names of the genre [shown] (trimmed), as it writes them; none it does not have. */
    suspend fun of(remote: AbsRemote, libraryId: String, shown: String): Set<String> {
        if (!byLibrary.containsKey(libraryId)) read(remote, libraryId)
        return byLibrary[libraryId]?.get(shown).orEmpty()
    }
}
