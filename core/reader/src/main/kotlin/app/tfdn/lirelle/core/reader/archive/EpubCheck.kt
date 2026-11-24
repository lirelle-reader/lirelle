package app.tfdn.lirelle.core.reader.archive

import android.util.Xml
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

enum class EpubProblem { NotAnArchive, NotAnEpub, Protected }

sealed interface EpubCheckResult {
    data object Ok : EpubCheckResult
    data class Refused(val problem: EpubProblem) : EpubCheckResult
}

private const val ContainerPath = "META-INF/container.xml"
private const val EncryptionPath = "META-INF/encryption.xml"

/** Either of these means a rights-management scheme: Adobe's, or Readium LCP. */
private val LicencePaths = listOf("META-INF/rights.xml", "META-INF/license.lcpl")

/** Font obfuscation, which the engine undoes; anything else is real encryption. */
private val Obfuscations = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

/** No honest container or encryption file comes near this; a larger one is not read. */
private const val MaxXmlBytes = 1024 * 1024

/**
 * Whether [file] is an EPUB the reader can open: a zip of no more than [MaxArchiveEntries]
 * entries, with a container naming a package that is in it, and nothing encrypted beyond
 * obfuscated fonts.
 */
fun checkEpub(file: File): EpubCheckResult {
    val zip = try {
        ZipFile(file)
    } catch (_: IOException) {
        return EpubCheckResult.Refused(EpubProblem.NotAnArchive)
    }
    val problem = zip.use(::problemOf)
    return problem?.let(EpubCheckResult::Refused) ?: EpubCheckResult.Ok
}

private fun problemOf(zip: ZipFile): EpubProblem? = when {
    zip.size() > MaxArchiveEntries -> EpubProblem.NotAnEpub
    !hasPackage(zip) -> EpubProblem.NotAnEpub
    LicencePaths.any { zip.getEntry(it) != null } -> EpubProblem.Protected
    isEncrypted(zip) -> EpubProblem.Protected
    else -> null
}

private fun hasPackage(zip: ZipFile): Boolean {
    val packages = attributes(zip, ContainerPath, element = "rootfile", attribute = "full-path").orEmpty()
    return packages.any { it.isNotEmpty() && zip.getEntry(it)?.isDirectory == false }
}

/** An encryption file that cannot be read counts as encryption: better a refusal than garbled pages. */
private fun isEncrypted(zip: ZipFile): Boolean {
    if (zip.getEntry(EncryptionPath) == null) return false
    val algorithms = attributes(zip, EncryptionPath, element = "EncryptionMethod", attribute = "Algorithm")
    return algorithms == null || algorithms.any { it !in Obfuscations }
}

/**
 * The value of [attribute] on every [element] of an XML entry, an absent attribute counting as
 * empty; null when the entry is missing, too large or not well-formed.
 *
 * See [parse] for what is refused.
 */
private fun attributes(zip: ZipFile, path: String, element: String, attribute: String): List<String>? = try {
    val bytes = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readNBytes(MaxXmlBytes + 1) } }
    bytes?.takeIf { it.size <= MaxXmlBytes }?.let { parse(it, element, attribute) }
} catch (_: IOException) {
    null
} catch (_: XmlPullParserException) {
    null
}

/**
 * A document with a doctype is refused as soon as the doctype is met. The platform's parser
 * replaces the entities a doctype declares, so a few lines can expand to gigabytes or stand for
 * a value they do not show; neither file of an EPUB has any use for one. Reading token by token
 * stops before anything after the doctype is looked at, and the parser fetches nothing by itself.
 */
private fun parse(xml: ByteArray, element: String, attribute: String): List<String> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(xml.inputStream(), null)
    val found = mutableListOf<String>()
    var token = parser.nextToken()
    while (token != XmlPullParser.END_DOCUMENT) {
        if (token == XmlPullParser.DOCDECL) throw XmlPullParserException("A doctype is not accepted")
        if (token == XmlPullParser.START_TAG && parser.name == element) {
            found += parser.getAttributeValue(null, attribute).orEmpty()
        }
        token = parser.nextToken()
    }
    return found
}
