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
    mainClass = "uk.kulikov.taskbar.samples.playground.MainKt"
}

// `./gradlew :playground:run -Pmode=Overlay` starts with the overlay fallback selected, which is
// handy when comparing the two attachment mechanisms.
tasks.withType<JavaExec>().configureEach {
    systemProperty("taskbar.mode", providers.gradleProperty("mode").getOrElse("Auto"))
}
