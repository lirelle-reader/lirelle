package app.tfdn.lirelle.feature.reader.text

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The ways the device lets the selected text leave the app. Nothing is sent until [perform] is called. */
interface TextActions {
    /**
     * What the device offers now, in the order of the bar and "More": Translate and Define only when an app
     * answers.
     */
    fun available(): List<TextAction>

    /**
     * Starts [action] with [text] cut at 10 000 characters; Copy puts it on the clipboard. False when nothing
     * could start it.
     */
    fun perform(action: TextAction, text: String): Boolean
}

/** Text handed over is cut here: it comes from an untrusted page, and intent extras must stay small. */
internal const val MAX_TEXT_LENGTH = 10_000

internal class DeviceTextActions @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextActions {

    override fun available(): List<TextAction> = buildList {
        if (answers(Intent(Intent.ACTION_TRANSLATE))) add(TextAction.Translate)
        if (answers(Intent(Intent.ACTION_DEFINE))) add(TextAction.Define)
        add(TextAction.Copy)
        add(TextAction.Share)
        add(TextAction.SearchWeb)
        addAll(textProcessors())
    }

    override fun perform(action: TextAction, text: String): Boolean {
        val cut = cut(text)
        return when (action) {
            TextAction.Copy -> copy(cut)
            TextAction.Translate -> start(Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, cut))
            TextAction.Define -> start(Intent(Intent.ACTION_DEFINE).putExtra(Intent.EXTRA_TEXT, cut))
            TextAction.Share -> start(Intent.createChooser(send(cut), null))
            TextAction.SearchWeb -> start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, cut))
            is TextAction.App -> start(process(action, cut))
        }
    }

    private fun send(text: String) = Intent(Intent.ACTION_SEND)
        .setType(PLAIN_TEXT)
        .putExtra(Intent.EXTRA_TEXT, text)

    private fun process(app: TextAction.App, text: String) = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType(PLAIN_TEXT)
        .setClassName(app.packageName, app.activityName)
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    private fun copy(text: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
        return true
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun query(intent: Intent): List<ResolveInfo> =
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))

    private fun answers(intent: Intent) = query(intent).isNotEmpty()

    private fun textProcessors(): List<TextAction.App> =
        query(Intent(Intent.ACTION_PROCESS_TEXT).setType(PLAIN_TEXT))
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                TextAction.App(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                )
            }
            .sortedBy { it.label.lowercase() }

    private fun cut(text: String): String {
        if (text.length <= MAX_TEXT_LENGTH) return text
        // Never end on half of a surrogate pair.
        val end = if (text[MAX_TEXT_LENGTH - 1].isHighSurrogate()) MAX_TEXT_LENGTH - 1 else MAX_TEXT_LENGTH
        return text.substring(0, end)
    }

    private companion object {
        const val PLAIN_TEXT = "text/plain"
    }
}
