// Maven Central through Google's mirror first: Maven Central rate-limits the CI's address (429) whenever a cold
// cache downloads everything. Maven Central itself stays behind it, for anything the mirror lacks.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "lirelle"

include(":app")
include(":core:designsystem")
include(":core:ids")
include(":core:session")
include(":core:network")
include(":core:testing")
include(":core:library")
include(":core:reader")
include(":core:audio")
include(":core:downloads")
include(":core:comics")
include(":core:history")
include(":core:ui")
include(":source:bookorbit")
include(":source:komga")
include(":feature:login")
include(":feature:account")
include(":feature:library")
include(":feature:book")
include(":feature:downloads")
include(":feature:home")
include(":feature:search")
include(":feature:reader")
include(":feature:comics")
include(":feature:history")
include(":feature:settings")
include(":feature:player")
