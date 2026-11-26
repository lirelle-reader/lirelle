package app.tfdn.lirelle.core.network

import okhttp3.Interceptor
import okhttp3.Response

private const val AppName = "Lirelle"
private const val Unknown = "unknown"
private const val UserAgentHeader = "User-Agent"
private const val Replacement = '_'

/** What a token (the version) may hold besides letters and digits, as HTTP defines it. */
private const val TokenSymbols = "!#$%&'*+-.^_`|~"

/** What a comment (Android's version, the model) may not hold although printable: it would end or split it. */
private const val CommentBreakers = "();\\"

/**
 * How Lirelle names itself to a server: [value] on every request, `Lirelle/0.7.0 (Android 16; SM-S931B)`, and its
 * first part, [app], `Lirelle/0.7.0`, which the sign-in WebView adds to its own agent. Nothing personal: the
 * device's model, never the name the user gave it, no account, no identifier.
 */
data class UserAgent(val app: String, val value: String)

/**
 * The [UserAgent] of this version of the app on this device. Each field is trimmed, every character a header
 * cannot carry — or that would break the agent's shape — becomes `_` (one per character, an emoji included), and
 * a field left blank says `unknown`: the result is always a value OkHttp accepts.
 */
fun formatUserAgent(version: String, androidRelease: String, model: String): UserAgent {
    val app = "$AppName/${version.cleaned(::isTokenCharacter)}"
    val comment = "Android ${androidRelease.cleaned(::isCommentCharacter)}; ${model.cleaned(::isCommentCharacter)}"
    return UserAgent(app = app, value = "$app ($comment)")
}

private fun isTokenCharacter(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in TokenSymbols

private fun isCommentCharacter(c: Char): Boolean = c in ' '..'~' && c !in CommentBreakers

private fun String.cleaned(keeps: (Char) -> Boolean): String {
    val field = trim()
    if (field.isEmpty()) return Unknown
    val out = StringBuilder(field.length)
    var i = 0
    while (i < field.length) {
        val codePoint = field.codePointAt(i)
        val c = codePoint.toChar()
        out.append(if (Character.isBmpCodePoint(codePoint) && keeps(c)) c else Replacement)
        i += Character.charCount(codePoint)
    }
    return out.toString()
}

/**
 * Puts [agent] on every request, in place of any agent already set (OkHttp's own, Media3's, Coil's): one name for
 * every request the app makes. Installed once, on the public client ([newPublicClient]), which every other client
 * is made from.
 */
class UserAgentInterceptor(private val agent: UserAgent) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(UserAgentHeader, agent.value).build())
}
