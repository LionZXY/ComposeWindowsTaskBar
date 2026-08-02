import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
    jvmToolchain(libs.versions.jvmTarget.get().toInt())

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
    }

    sourceSets.named("jvmMain") {
        dependencies {
            // Public: consumers write Compose code against these types.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)

            // Win32 interop. JNA ships its own native stubs inside its Maven artifacts, so
            // consumers get working native bindings transitively, with nothing to build,
            // extract, sign or unpack at runtime.
            api(libs.jna)
            api(libs.jna.platform)
        }
    }

    sourceSets.named("jvmTest") {
        dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// -----------------------------------------------------------------------------
// Publishing
//
// Maven Central's Central Portal does not accept plain Maven-protocol uploads for releases: it
// wants a signed bundle through its own API. This plugin builds and uploads that bundle, and
// knows how to lay out a Kotlin Multiplatform publication (root module plus per-target modules).
// -----------------------------------------------------------------------------

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    // Only wire signing up when a key is actually available, so `publishToMavenLocal` - which is
    // how the samples build consumes the library - keeps working on a machine with no GPG key.
    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
    if (hasSigningKey) signAllPublications()

    // Coordinates come from gradle.properties (GROUP / VERSION_NAME) and the module directory
    // name, which is this plugin's convention and is already finalised by the time this runs.
    // That is also why `-PVERSION_NAME=...` is how CI overrides the version for a release.

    pom {
        name.set("compose-windows-taskbar")
        description.set(
            "Host Compose for Desktop content directly inside the Windows taskbar, with a " +
                "WindowsTaskBar { } composable that mirrors Compose's own Window/Tray API."
        )
        url.set("https://github.com/LionZXY/ComposeWindowsTaskBar")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("LionZXY")
                name.set("Nikita Kulikov")
                url.set("https://github.com/LionZXY")
            }
        }
        scm {
            url.set("https://github.com/LionZXY/ComposeWindowsTaskBar")
            connection.set("scm:git:https://github.com/LionZXY/ComposeWindowsTaskBar.git")
            developerConnection.set("scm:git:ssh://git@github.com/LionZXY/ComposeWindowsTaskBar.git")
        }
    }
}
