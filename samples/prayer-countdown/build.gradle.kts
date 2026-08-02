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
    implementation(libs.compose.material)
    implementation(libs.compose.windows.taskbar)
}

compose.desktop.application {
    mainClass = "uk.kulikov.taskbar.samples.prayer.MainKt"
}
