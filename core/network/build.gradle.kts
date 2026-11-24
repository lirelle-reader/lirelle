plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.tfdn.lirelle.core.network"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// What is neutral about speaking to a server: the results and errors, the authenticated client, the network's
// state and diagnostics. Routes, answers and their translation are each source's (source/bookorbit).
dependencies {
    api(project(":core:ids"))
    api(project(":core:session"))
    api(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
}
