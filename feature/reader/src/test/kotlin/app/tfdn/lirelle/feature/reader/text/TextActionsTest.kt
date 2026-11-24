package app.tfdn.lirelle.feature.reader.text

import android.app.Application
import android.app.SearchManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TextActionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val actions: TextActions = DeviceTextActions(app)
    private val packages = shadowOf(app.packageManager)

    private fun answer(action: String, pkg: String, name: String, label: String, mime: String? = null) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                this.name = name
                applicationInfo = android.content.pm.ApplicationInfo(app.applicationInfo).also { it.packageName = pkg }
            }
            nonLocalizedLabel = label
        }
        val probe = Intent(action).apply { mime?.let { setType(it) } }
        packages.addResolveInfoForIntent(probe, info)
        packages.addOrUpdateActivity(info.activityInfo.also { it.exported = true })
    }

    @Test
    fun `without handlers neither Translate nor Define is offered`() {
        assertThat(actions.available()).containsExactly(
            TextAction.Copy, TextAction.Share, TextAction.SearchWeb,
        ).inOrder()
    }

    @Test
    fun `a translate handler adds Translate, started with the text`() {
        answer(Intent.ACTION_TRANSLATE, "x.tr", "x.tr.A", "Tr")

        assertThat(actions.available().first()).isEqualTo(TextAction.Translate)
        assertThat(actions.perform(TextAction.Translate, "bonjour")).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_TRANSLATE)
        assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("bonjour")
        assertThat(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `a define handler adds Define, started with the text`() {
        answer(Intent.ACTION_DEFINE, "x.df", "x.df.A", "Df")

        assertThat(actions.available()).contains(TextAction.Define)
        assertThat(actions.available()).doesNotContain(TextAction.Translate)
        assertThat(actions.perform(TextAction.Define, "mot")).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_DEFINE)
        assertThat(started.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("mot")
    }

    @Test
    fun `text processors are listed by label, never Lirelle itself, and started explicitly`() {
        answer(Intent.ACTION_PROCESS_TEXT, "x.zed", "x.zed.A", "Zed", "text/plain")
        answer(Intent.ACTION_PROCESS_TEXT, "x.abc", "x.abc.B", "abc", "text/plain")
        answer(Intent.ACTION_PROCESS_TEXT, app.packageName, "own.C", "Lirelle", "text/plain")

        val listed = actions.available().filterIsInstance<TextAction.App>()
        assertThat(listed.map { it.label }).containsExactly("abc", "Zed").inOrder()
        assertThat(listed.map { it.packageName }).doesNotContain(app.packageName)

        assertThat(actions.perform(listed.first(), "texte")).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_PROCESS_TEXT)
        assertThat(started.component).isEqualTo(ComponentName("x.abc", "x.abc.B"))
        assertThat(started.getStringExtra(Intent.EXTRA_PROCESS_TEXT)).isEqualTo("texte")
        assertThat(started.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)).isTrue()
    }

    @Test
    fun `Share goes through a chooser of ACTION_SEND`() {
        assertThat(actions.perform(TextAction.Share, "partage")).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(inner.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(inner.type).isEqualTo("text/plain")
        assertThat(inner.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("partage")
    }

    @Test
    fun `web search carries the text as the query`() {
        assertThat(actions.perform(TextAction.SearchWeb, "requete")).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_WEB_SEARCH)
        assertThat(started.getStringExtra(SearchManager.QUERY)).isEqualTo("requete")
    }

    @Test
    fun `Copy puts a plain text clip on the clipboard`() {
        assertThat(actions.perform(TextAction.Copy, "copie")).isTrue()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertThat(clip.description.hasMimeType("text/plain")).isTrue()
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo("copie")
    }

    @Test
    fun `text over 10 000 characters is cut`() {
        val long = "a".repeat(MAX_TEXT_LENGTH + 500)

        actions.perform(TextAction.SearchWeb, long)
        assertThat(
            shadowOf(app).nextStartedActivity.getStringExtra(SearchManager.QUERY)!!.length,
        ).isEqualTo(MAX_TEXT_LENGTH)

        actions.perform(TextAction.Copy, long)
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertThat(clip.getItemAt(0).text.length).isEqualTo(MAX_TEXT_LENGTH)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val text = "a".repeat(MAX_TEXT_LENGTH - 1) + "😀"

        actions.perform(TextAction.SearchWeb, text)

        val sent = shadowOf(app).nextStartedActivity.getStringExtra(SearchManager.QUERY)!!
        assertThat(sent.length).isEqualTo(MAX_TEXT_LENGTH - 1)
    }

    @Test
    fun `an activity that cannot be found returns false`() {
        shadowOf(app).checkActivities(true)

        assertThat(actions.perform(TextAction.SearchWeb, "x")).isFalse()
        assertThat(actions.perform(TextAction.App("n", "no.such", "no.such.A"), "x")).isFalse()
    }
}
