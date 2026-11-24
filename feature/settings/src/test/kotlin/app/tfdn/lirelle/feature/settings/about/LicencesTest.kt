package app.tfdn.lirelle.feature.settings.about

import android.util.Log
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * The list generated at build time, read into the components the screens show.
 *
 * Under Robolectric: the library reads the file with the platform's JSON classes.
 */
@RunWith(RobolectricTestRunner::class)
class LicencesTest {

    private val licences = licencesOf(licencesFixture())

    @Test
    fun `the bundled components are apart from the libraries`() {
        assertThat(licences.bundled.map { it.id }).containsExactly("bundled:engine", "bundled:reading-font")
        assertThat(licences.libraries.map { it.id })
            .containsExactly("org.example:alpha", "org.example:orphan", "org.example:zebra")
        assertThat(licences.bundled.all { it.bundled }).isTrue()
        assertThat(licences.libraries.none { it.bundled }).isTrue()
    }

    @Test
    fun `each group is sorted by name, case ignored`() {
        assertThat(licences.bundled.map { it.name }).containsExactly("engine", "Reading Font").inOrder()
        assertThat(licences.libraries.map { it.name }).containsExactly("Alpha", "Mango", "zebra").inOrder()
    }

    @Test
    fun `the author is the first developer, else the organisation, else nobody`() {
        assertThat(licences.component("org.example:zebra")?.author).isEqualTo("Zoe Zebra")
        assertThat(licences.component("org.example:alpha")?.author).isEqualTo("The Alpha Organisation")
        assertThat(licences.component("org.example:orphan")?.author).isNull()
    }

    @Test
    fun `a licence's short name is its SPDX id, else its name`() {
        val alpha = licences.component("org.example:alpha")!!

        assertThat(alpha.licences.map { it.shortName }).containsExactly("Apache-2.0", "Example Terms").inOrder()
        assertThat(alpha.licences.map { it.name }).containsExactly("Apache License 2.0", "Example Terms").inOrder()
        // A bundled component's entry has a name of its own in the file, and the usual short name.
        assertThat(licences.component("bundled:engine")!!.licences.single().shortName).isEqualTo("MIT")
    }

    @Test
    fun `a licence carries its text, or none`() {
        val engine = licences.component("bundled:engine")!!.licences.single()
        val orphan = licences.component("org.example:orphan")!!.licences.single()

        assertThat(engine.text).isEqualTo("MIT License\n\nCopyright (c) 2022 Ann Engineer")
        assertThat(orphan.text).isNull()
        assertThat(orphan.shortName).isEqualTo("BSD-3-Clause")
    }

    @Test
    fun `a component is found by its id, in either group`() {
        assertThat(licences.component("bundled:reading-font")?.name).isEqualTo("Reading Font")
        assertThat(licences.component("org.example:zebra")?.name).isEqualTo("zebra")
        assertThat(licences.component("org.example:absent")).isNull()
    }

    @Test
    fun `a component listed twice under the same name, author and licences shows once, the first by id`() {
        assertThat(licences.libraries.count { it.name == "zebra" }).isEqualTo(1)
        assertThat(licences.component("org.example:zebra")?.name).isEqualTo("zebra")
        assertThat(licences.component("org.example:zebra-android")).isNull()
    }

    @Test
    fun `components of the same name stay apart when the author or the licence differs`() {
        val read = licencesOf(sameNameJson())

        assertThat(read.libraries.map { it.id })
            .containsExactly("org.example:a-first", "org.example:c-other-author", "org.example:d-other-licence")
    }

    /** Four libraries of one name: a twin of the first, one by another author, one under another licence. */
    private fun sameNameJson(): String {
        fun library(id: String, author: String, licence: String) =
            """{"uniqueId": "$id", "name": "Same", "developers": [{"name": "$author"}], "licenses": ["$licence"]}"""
        val libraries = listOf(
            library("org.example:d-other-licence", "Ann", "Two"),
            library("org.example:b-twin", "Ann", "One"),
            library("org.example:c-other-author", "Bob", "One"),
            library("org.example:a-first", "Ann", "One"),
        )
        val licences = listOf("One", "Two")
            .joinToString { """"$it": {"hash": "$it", "name": "$it", "content": "$it"}""" }
        return """{"libraries": [${libraries.joinToString()}], "licenses": {$licences}}"""
    }

    @Test
    fun `what cannot be read gives empty lists`() {
        listOf("", "not json", "{\"libraries\": 3}", "[]", "{").forEach { malformed ->
            val read = licencesOf(malformed)

            assertThat(read.bundled).isEmpty()
            assertThat(read.libraries).isEmpty()
        }
    }

    @Test
    fun `what cannot be read is logged as one warning`() {
        listOf("", "not json", "{\"libraries\": 3}", "[]", "{").forEach { malformed ->
            ShadowLog.clear()

            licencesOf(malformed)

            // The library keeps a failure to itself and gives an empty list: the warning tells of that.
            val logged = ShadowLog.getLogsForTag("Licences").single()
            assertThat(logged.type).isEqualTo(Log.WARN)
            assertThat(logged.msg).startsWith("The list of licences")
        }
    }

    @Test
    fun `a list that reads logs nothing`() {
        ShadowLog.clear()

        licencesOf(licencesFixture())

        assertThat(ShadowLog.getLogsForTag("Licences")).isEmpty()
    }

    @Test
    fun `the app is a component of its own, under the GPL, apart from both lists`() {
        val app = licences.app

        assertThat(app.id).isEqualTo(AppLicenceId)
        assertThat(app.name).isEqualTo("Lirelle")
        assertThat(app.licences.single().shortName).isEqualTo("GPL-3.0-or-later")
        assertThat(app.licences.single().name).isEqualTo("GNU General Public License v3.0 or later")
        assertThat(licences.bundled + licences.libraries).doesNotContain(app)
    }

    @Test
    fun `the app is found by its id, with the text it was given`() {
        val withText = licences.copy(app = appComponent(licenceText = "GNU GENERAL PUBLIC LICENSE"))

        assertThat(withText.component(AppLicenceId)?.licences?.single()?.text).isEqualTo("GNU GENERAL PUBLIC LICENSE")
        assertThat(licences.component(AppLicenceId)?.licences?.single()?.text).isNull()
    }
}
