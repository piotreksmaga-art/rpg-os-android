plugins {
    id("com.android.application") version "9.3.1" apply false

    // AGP 9.x has built-in Kotlin support. Do NOT apply org.jetbrains.kotlin.android.
    // Compose with Kotlin 2.x still requires the Compose Compiler Gradle plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
