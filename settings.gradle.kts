pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform 1.11 resolves parts of AndroidX (lifecycle, savedstate,
        // runtime-retain) straight from Google's repository.
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "compose-windows-taskbar-build"

// The directory name is the Maven artifactId, so it is spelled out in full here rather than
// shortened to `taskbar`.
include(":compose-windows-taskbar")
