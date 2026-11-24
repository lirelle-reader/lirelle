package app.tfdn.lirelle.core.comics

/**
 * The names the comics are kept under. An installation from before the profiles kept the pages under the same
 * name at the root of the cache, and the directions in the device's settings: the migration moves them by
 * these names, so that it cannot look for one the stores do not use.
 */
object ComicsFiles {
    /** The folder, in a profile's cache, of the pages of the albums read. */
    const val Pages = "comics"

    /** The folder, in a profile's cache, of the zones found on the pages of the albums read panel by panel. */
    const val Panels = "panels"

    /** The device's preferences file of how comics are shown, which held the reading directions too. */
    const val Settings = "comics_settings"

    /** The profile's preferences file of the direction each of its series and albums is read in. */
    const val Directions = "comic_directions"
}
