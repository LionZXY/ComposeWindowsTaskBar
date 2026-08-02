// A deliberately separate Gradle build: the samples consume compose-windows-taskbar as a real
// published artifact from a repository, not as a project dependency. That is the only way to prove
// that a consumer gets everything they need - including JNA's native stubs - from the coordinate
// alone.
//
// Run `./gradlew publishToMavenLocal` in the parent build first.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // Where `./gradlew publishToMavenLocal` in the parent build puts the library.
        mavenLocal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "compose-windows-taskbar-samples"

include(":hello-taskbar")
include(":system-monitor")
include(":media-controls")
include(":prayer-countdown")
include(":multi-monitor-clock")
include(":playground")
