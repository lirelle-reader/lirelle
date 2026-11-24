plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.tfdn.lirelle.source.bookorbit"
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

// -PwriteModelFixtures=true: ModelFixturesTest writes core:testing's model fixtures instead of checking them.
val writeModelFixtures = providers.gradleProperty("writeModelFixtures").orElse("false")
tasks.withType<Test>().configureEach {
    systemProperty("writeModelFixtures", writeModelFixtures.get())
}

// Everything that speaks BookOrbit: its routes, its answers and the implementations of the core modules' contracts.
// Only the app depends on it.
dependencies {
    implementation(project(":core:ids"))
    implementation(project(":core:session"))
    implementation(project(":core:network"))
    implementation(project(":core:library"))
    implementation(project(":core:history"))
    implementation(project(":core:comics"))
    implementation(project(":core:downloads"))
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The model fixtures it checks are core:testing's.
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.paging.testing)
}
