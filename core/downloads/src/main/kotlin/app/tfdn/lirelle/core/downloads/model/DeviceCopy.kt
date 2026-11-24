package app.tfdn.lirelle.core.downloads.model

/**
 * What the device keeps of the finished book, for the end panel's row; null when nothing is kept.
 *
 * @property formats those the device has whole, in the order of [DownloadFormat].
 * @property bytes everything the book has on the device, what is still being fetched included: all of it goes
 *   with a deletion.
 * @property deleting the deletion was asked: the book goes once the reader and the player let go of it.
 * @property textName what the text is named by, when it is among [formats]: "CBZ", "PDF".
 */
data class DeviceCopy(
    val formats: List<DownloadFormat>,
    val bytes: Long,
    val deleting: Boolean,
    val textName: TextName = TextName.Text,
)

/** The copy the end panels show of this book; null when no format is whole. A format being deleted is not counted. */
fun DownloadedBook.deviceCopy(): DeviceCopy? {
    val staying = copy(formats = formats.filterNot { it.removing })
    val whole = staying.formats.filter { it.state is FormatState.Complete }.sortedBy { it.format }
    val text = whole.firstOrNull { it.format == DownloadFormat.Text }?.textName ?: TextName.Text
    return DeviceCopy(whole.map { it.format }, staying.bytesOnDevice, deleteAtClose, text)
        .takeIf { whole.isNotEmpty() }
}
