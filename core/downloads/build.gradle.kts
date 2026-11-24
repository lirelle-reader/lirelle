plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.tfdn.lirelle.core.downloads"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    testOptions {
        unitTests {
            // The notifications are built from the strings.
            isIncludeAndroidResources = true
            all {
                // Robolectric reaches into FileDescriptor internals, which JDK 25 no longer exports.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    api(project(":core:library"))
    // Whose downloads they are, the EPUB check and the read-along map.
    api(project(":core:reader"))
    api(project(":core:audio"))
    // The books served by pages: whether a book is, their count, and the pages the page reader has seen.
    implementation(project(":core:comics"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
