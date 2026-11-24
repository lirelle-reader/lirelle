package app.tfdn.lirelle.core.downloads

/**
 * The names a profile's downloads are kept under, in its folders. An installation from before the profiles
 * kept them under the same names at the root of the application's own folders: the migration moves them by
 * these, so that it cannot look for a name the stores do not use.
 */
object DownloadsFiles {
    /** The preferences file of what is kept of each book, and of the hour the kept books were last checked. */
    const val Store = "downloads"

    /** The folder of the files themselves. */
    const val Folder = "downloads"
}
