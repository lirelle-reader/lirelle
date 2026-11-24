plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.tfdn.lirelle.core.comics"
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
            all {
                // Robolectric reaches into FileDescriptor internals, which JDK 25 no longer exports.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
                // The bench of the panel cut reads real pages kept outside the repository, when told where.
                System.getProperty("panels.pages")?.let { pages -> it.systemProperty("panels.pages", pages) }
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    api(project(":core:library"))
    // A PDF is fetched whole into the reader's cache of whole files.
    implementation(project(":core:reader"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    // A page's size is read by the platform's own image decoder.
    testImplementation(libs.robolectric)
}
