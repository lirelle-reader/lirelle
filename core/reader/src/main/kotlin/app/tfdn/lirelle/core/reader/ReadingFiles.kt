package app.tfdn.lirelle.core.reader

/**
 * The names a profile's reading is kept under, in its folders. An installation from before the profiles kept
 * it under the same names at the root of the application's own folders: the migration moves it by these, so
 * that it cannot look for a name the stores do not use.
 */
object ReadingFiles {
    /** The preferences file of the positions, the narrations, what waits to be sent and the marks. */
    const val Store = "reading"

    /** The folder, in the cache, of the books opened in the reader. */
    const val Cache = "reader"

    /** The key the first format of the marks was kept under in [Store]: never read, only dropped. */
    const val FirstMarks = "marks"
}
