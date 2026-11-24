package app.tfdn.lirelle.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.feature.settings.about.AppLicenceId
import app.tfdn.lirelle.feature.settings.about.Licences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The list the build generated, as the app reads it: the real resource, not a fixture. */
@RunWith(RobolectricTestRunner::class)
class LicencesInResourcesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun generated(): Licences = runBlocking { LicencesInResources(context.resources).load() }

    @Test
    fun `the five bundled components are in the generated list`() {
        assertThat(generated().bundled.map { it.name })
            .containsExactly("foliate-js", "fflate", "Literata", "Atkinson Hyperlegible", "OpenDyslexic")
    }

    @Test
    fun `the libraries of the app are in the generated list`() {
        assertThat(generated().libraries.size).isGreaterThan(100)    }

    @Test
    fun `every component has a licence, and every licence its text`() {
        val licences = generated()
        val components = licences.bundled + licences.libraries

        assertThat(components.filter { it.licences.isEmpty() }.map { it.id }).isEmpty()
        assertThat(components.filter { component -> component.licences.any { it.text.isNullOrBlank() } }.map { it.id })
            .isEmpty()
    }

    @Test
    fun `the Protocol Buffers licence is the text its artifact ships, under its copyright line`() {
        val protobuf = generated().component("androidx.datastore:datastore-preferences-external-protobuf")

        // One licence only: the entry of its own replaces the detected one, it does not add to it.
        val licence = checkNotNull(protobuf).licences.single()
        assertThat(licence.shortName).isEqualTo("BSD-3-Clause")
        assertThat(licence.text).startsWith(
            "Copyright 2008 Google Inc.  All rights reserved.\n\nRedistribution and use in source and binary forms",
        )
        assertThat(licence.text).doesNotContain("<owner>")
    }

    @Test
    fun `the Protocol Buffers library keeps what the list says of it`() {
        val protobuf = generated().component("androidx.datastore:datastore-preferences-external-protobuf")

        assertThat(protobuf?.name).isEqualTo("Preferences External Protobuf")
        assertThat(protobuf?.author).isEqualTo("The Android Open Source Project")
        assertThat(protobuf?.bundled).isFalse()
    }

    @Test
    fun `a list that cannot be read gives empty lists, and the app's licence still`() {
        val licences = runBlocking { LicencesInResources(context.resources, resource = 0).load() }

        assertThat(licences.bundled).isEmpty()
        assertThat(licences.libraries).isEmpty()
        assertThat(licences.app.licences.single().text).contains("GNU GENERAL PUBLIC LICENSE")
    }

    @Test
    fun `the app's licence is the text of the repository's LICENSE file`() {
        // Unit tests run from the module's directory: the repository's root is its parent.
        val repositoryLicence = File("../LICENSE").readText()

        val app = checkNotNull(generated().component(AppLicenceId))

        assertThat(app.licences.single().text).isEqualTo(repositoryLicence)
    }

    @Test
    fun `an app licence that cannot be read leaves its text out, and the lists as they are`() {
        val licences = runBlocking { LicencesInResources(context.resources, appLicence = 0).load() }

        assertThat(licences.app.licences.single().text).isNull()
        assertThat(licences.libraries).isNotEmpty()
    }
}
