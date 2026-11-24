package app.tfdn.lirelle.core.reader.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val ContainerPath = "META-INF/container.xml"
internal const val EncryptionPath = "META-INF/encryption.xml"

internal const val Container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

/** The least a zip needs to pass for an EPUB, with [more] entries added or replacing those. */
internal fun epub(file: File, vararg more: Pair<String, String>): File {
    val least = mapOf(
        "mimetype" to "application/epub+zip",
        ContainerPath to Container,
        "OEBPS/content.opf" to "<package/>",
    )
    return zip(file, *(least + more).toList().toTypedArray())
}

/** A zip of text entries; a name ending with a slash is a directory. */
internal fun zip(file: File, vararg entries: Pair<String, String>): File {
    ZipOutputStream(file.outputStream()).use { out ->
        entries.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            if (!name.endsWith("/")) out.write(text.toByteArray())
            out.closeEntry()
        }
    }
    return file
}

/** An `encryption.xml` declaring one encrypted resource per algorithm. */
internal fun encryption(vararg algorithms: String, prolog: String = ""): String =
    """<?xml version="1.0" encoding="UTF-8"?>$prolog
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
    xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
${
        algorithms.joinToString("\n") {
            """  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="$it"/>
    <enc:CipherData><enc:CipherReference URI="OEBPS/fonts/a.otf"/></enc:CipherData>
  </enc:EncryptedData>"""
        }
    }
</encryption>"""

/** The least EPUB, filled with empty entries up to [count] in all. */
internal fun epubOfEntries(file: File, count: Int): File {
    val least = listOf(
        "mimetype" to "application/epub+zip",
        ContainerPath to Container,
        "OEBPS/content.opf" to "<package/>",
    )
    ZipOutputStream(file.outputStream()).use { out ->
        least.forEach { (name, text) ->
            out.putNextEntry(ZipEntry(name))
            out.write(text.toByteArray())
            out.closeEntry()
        }
        repeat(count - least.size) {
            out.putNextEntry(ZipEntry("OEBPS/filler/$it"))
            out.closeEntry()
        }
    }
    return file
}

private const val DirectoryHeaderBytes = 46
private const val SizeOffset = 24
private val DirectorySignature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)

/**
 * Rewrites the uncompressed size the archive's directory declares for [name], leaving the data as
 * it is: what a crafted archive does to look smaller, or larger, than it is.
 */
internal fun declareSize(file: File, name: String, size: Long): File {
    val bytes = file.readBytes()
    val wanted = name.toByteArray()
    val header = (0..bytes.size - DirectoryHeaderBytes - wanted.size).first { at ->
        DirectorySignature.indices.all { bytes[at + it] == DirectorySignature[it] } &&
            wanted.indices.all { bytes[at + DirectoryHeaderBytes + it] == wanted[it] }
    }
    repeat(Int.SIZE_BYTES) { bytes[header + SizeOffset + it] = (size shr (it * Byte.SIZE_BITS)).toByte() }
    file.writeBytes(bytes)
    return file
}
