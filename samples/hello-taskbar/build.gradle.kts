plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // The whole integration: one coordinate. JNA and its native stubs arrive transitively.
    implementation(libs.compose.windows.taskbar)
}

compose.desktop.application {
    mainClass = "uk.kulikov.taskbar.samples.hello.MainKt"
}
