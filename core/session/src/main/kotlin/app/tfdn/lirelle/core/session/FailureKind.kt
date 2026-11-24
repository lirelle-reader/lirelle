package app.tfdn.lirelle.core.session

/** How many causes of a failure are named. */
private const val CausesNamed = 4

/** How many places of its trace are given. */
private const val FramesNamed = 6

/**
 * What a log may be told of a failure about a profile: the classes of the failure and of its causes, and the
 * first places of its trace. Never its message, which may quote a path, a server's address, an account or a
 * title, and a log leaves the device with a bug report.
 *
 * The places are given as they are, whichever code they are in: a release build renames the application's own
 * classes, so that looking for them by name would find none.
 */
fun Throwable.kindAndFrames(): String {
    val kinds = generateSequence(this) { it.cause }.take(CausesNamed).joinToString(" <- ") { it.javaClass.name }
    val frames = stackTrace.take(FramesNamed)
        .joinToString(" < ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    return if (frames.isEmpty()) kinds else "$kinds at $frames"
}
