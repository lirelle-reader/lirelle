package app.tfdn.lirelle.core.downloads.model

/** The choice after a tap on [format]: the narration takes the text with it, which then cannot be unchecked. */
fun Set<DownloadFormat>.toggled(format: DownloadFormat): Set<DownloadFormat> = when {
    format == DownloadFormat.Text && DownloadFormat.Narration in this -> this
    format in this -> this - format
    format == DownloadFormat.Narration -> this + format + DownloadFormat.Text
    else -> this + format
}
