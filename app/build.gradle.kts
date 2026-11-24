import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutLibraries.android)
}

val versionBase = providers.gradleProperty("lirelle.versionBase").get()
val pipelineIid = providers.environmentVariable("CI_PIPELINE_IID").orNull?.toInt() ?: 1

// The signing variables are LIRELLE_<name>, set by the CI; one set empty counts as not set.
fun signingVariable(name: String) =
    providers.environmentVariable("LIRELLE_$name").filter { it.isNotEmpty() }

val keystoreFile = signingVariable("KEYSTORE_FILE").orNull

android {
    namespace = "app.tfdn.lirelle"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "app.tfdn.lirelle"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = pipelineIid
        versionName = "$versionBase.$pipelineIid"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = signingVariable("KEYSTORE_PASSWORD").get()
                keyAlias = signingVariable("KEY_ALIAS").get()
                keyPassword = signingVariable("KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // Lets a debug build sit beside the release build installed by Obtainium.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
        // Optimised like the release, but signed with the debug key and installed in place of the
        // debug build: for judging speed on a device before a merge request. Never published.
        create("localRelease") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric reaches into FileDescriptor internals, which JDK 25 no longer exports.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }

    lint {
        checkDependencies = true
        warningsAsErrors = false
        abortOnError = true
        error += setOf("MissingTranslation", "ExtraTranslation", "HardcodedText")
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// The list of what the app ships and its licences, written at build time into the raw resource
// `aboutlibraries`, which the licences' screens read.
aboutLibraries {
    // Nothing is fetched: the licence texts are in the configuration directory.
    offlineMode = true
    // The licence texts, and the components that ship outside Gradle (the reading engine, the fonts).
    collect { configPath = file("../config/aboutlibraries") }
    export {
        prettyPrint = true
        excludeFields.addAll("funding")
    }
    library {
        requireLicense = true
        // Protocol Buffers comes under a licence entry of its own, which carries its copyright line. An
        // entry of the configuration directory merges into the detected one and would add its licence to
        // the detected one, so the detected entry is left out and the one of the directory stands alone.
        exclusionPatterns.add(Pattern.compile("androidx\\.datastore:datastore-preferences-external-protobuf"))
    }
    // A dependency under a licence that is not listed here fails the build: it is seen before it ships.
    // Lirelle is under the GPL-3.0-or-later: every licence listed here lets its code be combined with it (the fonts,
    // under the OFL, are shipped beside it), and one is added only once that is checked (the GPL-2.0-only or a
    // proprietary licence would not be).
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0",
            "BSD-3-Clause",
            // The bundled components: the check goes by a licence's SPDX id, where it has one.
            "MIT",
            "OFL-1.1",
            "MIT-foliate-js",
            "MIT-fflate",
            "OFL-1.1-literata",
            "OFL-1.1-atkinson-hyperlegible",
            "OFL-1.1-opendyslexic",
        )
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":feature:login"))
    implementation(project(":feature:account"))
    implementation(project(":core:library"))
    implementation(project(":core:reader"))
    implementation(project(":core:audio"))
    implementation(project(":core:downloads"))
    implementation(project(":core:comics"))
    implementation(project(":core:ui"))
    // The BookOrbit source: the implementations of the contracts for a BookOrbit profile.
    implementation(project(":source:bookorbit"))
    // The Komga source: the implementations of the contracts for a Komga profile.
    implementation(project(":source:komga"))
    implementation(project(":feature:library"))
    implementation(project(":feature:book"))
    implementation(project(":feature:home"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:comics"))
    implementation(project(":feature:player"))
    implementation(project(":feature:search"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // For the migration of an installation from before the profiles, which opens the old preferences files.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
