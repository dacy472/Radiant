plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    // KSP replaces kapt for Room's annotation processor — kapt's javac bridge relies on a
    // bundled kotlin-metadata-jvm reader that caps out below Kotlin 2.4's metadata version.
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
