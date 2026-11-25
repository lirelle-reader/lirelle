package app.tfdn.lirelle.core.library

/**
 * The names of the library's preferences files. An installation from before the profiles kept the recent
 * searches in the device's file: the migration moves them by these names, so that it cannot look for one the
 * stores do not use.
 */
object LibraryFiles {
    /** The device's preferences file of how each list is shown, which held the recent searches too. */
    const val Views = "library"

    /** The profile's preferences file of its recent searches. */
    const val Searches = "searches"

    /** The profile's preferences file of the library its Library destination shows, and of its server's libraries. */
    const val Choice = "library_choice"
}
