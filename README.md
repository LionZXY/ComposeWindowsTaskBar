# Compose for Windows TaskBar

[![Maven Central](https://img.shields.io/maven-central/v/uk.kulikov/compose-windows-taskbar?label=Maven%20Central)](https://central.sonatype.com/artifact/uk.kulikov/compose-windows-taskbar)
[![CI](https://github.com/LionZXY/ComposeWindowsTaskBar/actions/workflows/ci.yml/badge.svg)](https://github.com/LionZXY/ComposeWindowsTaskBar/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Platform](https://img.shields.io/badge/platform-Windows%2010%20%7C%2011-0078D4?logo=windows&logoColor=white)](#how-it-works)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Put Compose for Desktop content **inside the Windows taskbar**. Not the tray — the taskbar itself.

![A Compose widget living in the Windows 11 taskbar](docs/taskbar-widget.png)

## Install

```kotlin
dependencies {
    implementation("uk.kulikov:compose-windows-taskbar:0.1.0")
}
```

Native bindings ship with it — JNA's stubs arrive transitively, nothing to build or unpack.

## Use

```kotlin
fun main() = application {
    WindowsTaskBar {
        Text("Hello, taskbar")
    }
}
```

It behaves like Compose's own `Window` / `Tray`: declarative, recomposes live, and keeps
`application { }` alive while composed. On non-Windows it composes nothing and reports
`TaskBarError.UnsupportedOs` — multiplatform apps keep building.

## Examples

![](docs/animated-widget.gif)
![](docs/video-widget.gif)
![](docs/next-event-flyout.png)

## Options

| Parameter | Default | What it does |
|---|---|---|
| `visible` | `true` | Show/hide without tearing the widget down |
| `size` | `180.dp × taskbar height` | Unspecified height = as tall as the taskbar |
| `alignment` | `BeforeTray` | `Start`, `AfterStart`, `AfterTaskList`, `Center`, `BeforeTray`, `End`, `Absolute(dp)` |
| `targets` | `Primary` | `Primary`, `All`, `Secondary`, `Monitor(deviceName)` — one instance per taskbar |
| `movable` | `true` | Drag along the taskbar; offset lands in `TaskBarState.offset` |
| `clickable` / `clickThrough` | `true` / `false` | Make the widget inert (`WS_EX_TRANSPARENT`) |
| `injectionMode` | `Auto` | `Reparent`, or `Overlay` as a fallback |
| `onClick` / `onRightClick` | `null` | Fire for clicks the content did not consume |
| `contextMenu` | `null` | `item` / `checkbox` / `separator` / `submenu` DSL |
| `onStatusChanged` | `{}` | Observe injection, `explorer.exe` restarts, failures |

Content is clipped to the taskbar, so anything bigger goes in a `Flyout` — a borderless window
anchored just off the taskbar, which *can* take focus and dismisses itself on click-away.

![Flyout panel](docs/flyout.png) ![Context menu](docs/context-menu.png)

## How it works

Windows has no API for this. Deskbands (`IDeskBand`) were removed in Windows 11, so — like every
current taskbar-widget project — this creates a borderless, transparent Compose window and makes it
a `WS_CHILD` of `Shell_TrayWnd` via `SetParent`. It then reconciles against the live taskbar
geometry a few times a second, so it survives `explorer.exe` restarts, taskbar moves, DPI changes
and display changes.

Run `TaskBarDiagnostics.report()` to see exactly what the library sees.

## Samples

Separate Gradle build that consumes the published artifact — `./gradlew publishToMavenLocal`, then
`./gradlew -p samples :hello-taskbar:run`.

| Sample | Shows |
|---|---|
| `hello-taskbar` | The ten-line version |
| `system-monitor` | `clickThrough`, `Start` alignment, coexisting with `Tray` |
| `media-controls` | `movable`, transport buttons, scrubber flyout |
| `prayer-countdown` | Countdown + settings panel (an Awqat-Salaat-style widget) |
| `multi-monitor-clock` | `targets = All`, consumed from a KMP build |
| `playground` | Every option wired to a control |
