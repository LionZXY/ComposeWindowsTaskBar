// This sample is a Kotlin Multiplatform consumer rather than a plain JVM one, so the published
// Gradle module metadata gets exercised from both angles: `hello-taskbar` resolves the `jvm`
// variant through the kotlin-jvm plugin, this one through a KMP `jvm("desktop")` target whose
// name does not even match the library's.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)

    jvm("desktop")

    sourceSets.named("desktopMain") {
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.windows.taskbar)
        }
    }
}

compose.desktop.application {
    mainClass = "uk.kulikov.taskbar.samples.clock.MainKt"
}
