plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutLibraries.android) apply false
    alias(libs.plugins.detekt)
}

// Robolectric downloads its Android images itself, from Maven Central unless told: through the same mirror.
val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", mavenCentralMirror)
        systemProperty("robolectric.dependency.repo.id", "maven-central-mirror")    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    )
}
