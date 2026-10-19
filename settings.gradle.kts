pluginManagement {
    // quickjs-kt 1.0.15 carries Kotlin 2.4 metadata; the bundled R8 is older.
    buildscript {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools:r8:9.1.56") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("cz.adaptech.tesseract4android") } } }
}
rootProject.name = "LocalLedger"
include(":app", ":fixtures")
