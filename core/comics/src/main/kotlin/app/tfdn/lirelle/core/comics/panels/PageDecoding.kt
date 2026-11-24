package app.tfdn.lirelle.core.comics.panels

/** What comes of reading a page's file for the cut. */
internal sealed interface PageDecoding {
    /** The picture, as the cut reads it. */
    class Picture(val pixels: PagePixels) : PageDecoding

    /**
     * The file is there and is no picture the device can read, or a picture the cut does not handle (one to be
     * turned before it is shown): that will not change, the page is not cut.
     */
    data object NoPicture : PageDecoding

    /** No room for the picture just now, or the file is gone: the page may be cut another time. */
    data object NotNow : PageDecoding
}
