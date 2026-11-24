plugins {
    alias(libs.plugins.android.library)
    // The model fixtures are read from the app's own JSON.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.tfdn.lirelle.core.testing"
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

dependencies {
    api(project(":core:network"))
    api(project(":core:library"))
    api(project(":core:reader"))
    api(project(":core:comics"))
    api(libs.androidx.paging.testing)
    api(libs.junit)
    api(libs.truth)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)

    // For the text layout assertions and the device rule shared by the screen tests.
    api(platform(libs.compose.bom))
    api(libs.compose.ui.test.junit4)
    api(libs.robolectric)
}
