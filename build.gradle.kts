// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build files.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // The Compose Compiler Gradle plugin; versioned together with Kotlin from 2.0 on.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
