Implement library for Compose Desktop to draw Compose as TaskBar Windows widget.
## Background
Right now Windows has unpopular API to inject view into TaskBar. Important: This isn't the same as tray (https://github.com/NucleusFramework/ComposeNativeTray).
You should implement library which inject Compose canvas into TaskBar and provide API for this.

## Suggested API

I expect to see simple API with two step:
1. Add KMP dependency to Gradle
2. In any place of compose code call `WindowTaskBar` (the same as for `Dialog`, `Window` or `Tray`):
```
fun main() = application {
    WindowsTaskBar() {
        // Content of the taskbar
    }
}
```
Use `uk.kulikov.*` package
## Optional parameters

Investigate projects using the same Windows API (see references and make your own research) and add optional parameters to `WindowTaskBar` like is movable, clickable (including right click) and etc.
Make sure that all existing project on other tech stack can be implemented using Compose Desktop + this library

## Validation

Implement several sample projects and connect library as external dependency. Make sure that native bindings also provided with dependency 

## References
Compose Multiplatform sources:
https://github.com/JetBrains/compose-multiplatform
Two projects with taskbar widgets:
https://github.com/Khiro95/Awqat-Salaat
https://github.com/okt/11-taskbar-widgets